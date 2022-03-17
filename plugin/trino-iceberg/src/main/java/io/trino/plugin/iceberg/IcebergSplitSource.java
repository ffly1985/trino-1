/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.trino.plugin.iceberg;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Stopwatch;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterators;
import com.google.common.collect.Streams;
import io.airlift.units.DataSize;
import io.airlift.units.Duration;
import io.trino.plugin.hive.HdfsEnvironment;
import io.trino.plugin.hive.InternalHiveSplit;
import io.trino.spi.HostAddress;
import io.trino.spi.TrinoException;
import io.trino.spi.connector.*;
import io.trino.spi.predicate.Domain;
import io.trino.spi.predicate.NullableValue;
import io.trino.spi.predicate.Range;
import io.trino.spi.predicate.TupleDomain;
import io.trino.spi.predicate.ValueSet;
import io.trino.spi.type.TypeManager;
import org.apache.hadoop.fs.BlockLocation;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.iceberg.CombinedScanTask;
import org.apache.iceberg.DataFile;
import org.apache.iceberg.FileScanTask;
import org.apache.iceberg.Schema;
import org.apache.iceberg.TableScan;
import org.apache.iceberg.expressions.Expression;
import org.apache.iceberg.io.CloseableIterable;
import org.apache.iceberg.types.Type;

import javax.annotation.Nullable;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

import static com.google.common.base.Preconditions.checkState;
import static com.google.common.base.Suppliers.memoize;
import static com.google.common.base.Verify.verify;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static com.google.common.collect.Sets.intersection;
import static io.trino.plugin.iceberg.ExpressionConverter.toIcebergExpression;
import static io.trino.plugin.iceberg.IcebergSplitManager.ICEBERG_DOMAIN_COMPACTION_THRESHOLD;
import static io.trino.plugin.iceberg.IcebergTypes.convertIcebergValueToTrino;
import static io.trino.plugin.iceberg.IcebergUtil.deserializePartitionValue;
import static io.trino.plugin.iceberg.IcebergUtil.getColumnHandle;
import static io.trino.plugin.iceberg.IcebergUtil.getPartitionKeys;
import static io.trino.plugin.iceberg.IcebergUtil.primitiveFieldTypes;
import static io.trino.plugin.iceberg.TypeConverter.toIcebergType;
import static io.trino.spi.StandardErrorCode.NOT_SUPPORTED;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.CompletableFuture.completedFuture;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.apache.iceberg.types.Conversions.fromByteBuffer;

public class IcebergSplitSource
        implements ConnectorSplitSource
{
    private static final ConnectorSplitBatch EMPTY_BATCH = new ConnectorSplitBatch(ImmutableList.of(), false);
    private static final ConnectorSplitBatch NO_MORE_SPLITS_BATCH = new ConnectorSplitBatch(ImmutableList.of(), true);

    private final IcebergTableHandle tableHandle;
    private final TableScan tableScan;
    private final Optional<Long> maxScannedFileSizeInBytes;
    private final Map<Integer, Type.PrimitiveType> fieldIdToType;
    private final DynamicFilter dynamicFilter;
    private final long dynamicFilteringWaitTimeoutMillis;
    private final Stopwatch dynamicFilterWaitStopwatch;
    private final Constraint constraint;
    private final TypeManager typeManager;

    private CloseableIterable<CombinedScanTask> combinedScanIterable;
    private Iterator<FileScanTask> fileScanIterator;
    private TupleDomain<IcebergColumnHandle> pushedDownDynamicFilterPredicate;

    private final boolean recordScannedFiles;
    private final ImmutableSet.Builder<DataFile> scannedFiles = ImmutableSet.builder();

    public IcebergSplitSource(
            IcebergTableHandle tableHandle,
            TableScan tableScan,
            Optional<DataSize> maxScannedFileSize,
            DynamicFilter dynamicFilter,
            Duration dynamicFilteringWaitTimeout,
            Constraint constraint,
            TypeManager typeManager,
            boolean recordScannedFiles)
    {
        this.tableHandle = requireNonNull(tableHandle, "tableHandle is null");
        this.tableScan = requireNonNull(tableScan, "tableScan is null");
        this.maxScannedFileSizeInBytes = requireNonNull(maxScannedFileSize, "maxScannedFileSize is null").map(DataSize::toBytes);
        this.fieldIdToType = primitiveFieldTypes(tableScan.schema());
        this.dynamicFilter = requireNonNull(dynamicFilter, "dynamicFilter is null");
        this.dynamicFilteringWaitTimeoutMillis = requireNonNull(dynamicFilteringWaitTimeout, "dynamicFilteringWaitTimeout is null").toMillis();
        this.dynamicFilterWaitStopwatch = Stopwatch.createStarted();
        this.constraint = requireNonNull(constraint, "constraint is null");
        this.typeManager = requireNonNull(typeManager, "typeManager is null");
        this.recordScannedFiles = recordScannedFiles;
    }

    @Override
    public CompletableFuture<ConnectorSplitBatch> getNextBatch(ConnectorPartitionHandle partitionHandle, int maxSize)
    {
        long timeLeft = dynamicFilteringWaitTimeoutMillis - dynamicFilterWaitStopwatch.elapsed(MILLISECONDS);
        if (dynamicFilter.isAwaitable() && timeLeft > 0) {
            return dynamicFilter.isBlocked()
                    .thenApply(ignored -> EMPTY_BATCH)
                    .completeOnTimeout(EMPTY_BATCH, timeLeft, MILLISECONDS);
        }

        if (combinedScanIterable == null) {
            // Used to avoid duplicating work if the Dynamic Filter was already pushed down to the Iceberg API
            this.pushedDownDynamicFilterPredicate = dynamicFilter.getCurrentPredicate().transformKeys(IcebergColumnHandle.class::cast);
            TupleDomain<IcebergColumnHandle> fullPredicate = tableHandle.getUnenforcedPredicate()
                    .intersect(pushedDownDynamicFilterPredicate);
            // TODO: (https://github.com/trinodb/trino/issues/9743): Consider removing TupleDomain#simplify
            TupleDomain<IcebergColumnHandle> simplifiedPredicate = fullPredicate.simplify(ICEBERG_DOMAIN_COMPACTION_THRESHOLD);
            if (!simplifiedPredicate.equals(fullPredicate)) {
                // Pushed down predicate was simplified, always evaluate it against individual splits
                this.pushedDownDynamicFilterPredicate = TupleDomain.all();
            }

            TupleDomain<IcebergColumnHandle> effectivePredicate = tableHandle.getEnforcedPredicate()
                    .intersect(simplifiedPredicate);

            if (effectivePredicate.isNone()) {
                finish();
                return completedFuture(NO_MORE_SPLITS_BATCH);
            }

            Expression filterExpression = toIcebergExpression(effectivePredicate);
            this.combinedScanIterable = tableScan
                    .filter(filterExpression)
                    .includeColumnStats()
                    .planTasks();
            this.fileScanIterator = Streams.stream(combinedScanIterable)
                    .map(CombinedScanTask::files)
                    .flatMap(Collection::stream)
                    .iterator();
        }

        TupleDomain<IcebergColumnHandle> dynamicFilterPredicate = dynamicFilter.getCurrentPredicate()
                .transformKeys(IcebergColumnHandle.class::cast);
        if (dynamicFilterPredicate.isNone()) {
            finish();
            return completedFuture(NO_MORE_SPLITS_BATCH);
        }

        Iterator<FileScanTask> fileScanTasks = Iterators.limit(fileScanIterator, maxSize);
        ImmutableList.Builder<ConnectorSplit> splits = ImmutableList.builder();
        while (fileScanTasks.hasNext()) {
            FileScanTask scanTask = fileScanTasks.next();
            if (!scanTask.deletes().isEmpty()) {
                throw new TrinoException(NOT_SUPPORTED, "Iceberg tables with delete files are not supported: " + tableHandle.getSchemaTableName());
            }

            if (maxScannedFileSizeInBytes.isPresent() && scanTask.file().fileSizeInBytes() > maxScannedFileSizeInBytes.get()) {
                continue;
            }

            IcebergSplit icebergSplit = this.toIcebergSplit(scanTask);

            Schema fileSchema = scanTask.spec().schema();
            Set<IcebergColumnHandle> identityPartitionColumns = icebergSplit.getPartitionKeys().keySet().stream()
                    .map(fieldId -> getColumnHandle(fileSchema.findField(fieldId), typeManager))
                    .collect(toImmutableSet());

            Supplier<Map<ColumnHandle, NullableValue>> partitionValues = memoize(() -> {
                Map<ColumnHandle, NullableValue> bindings = new HashMap<>();
                for (IcebergColumnHandle partitionColumn : identityPartitionColumns) {
                    Object partitionValue = deserializePartitionValue(
                            partitionColumn.getType(),
                            icebergSplit.getPartitionKeys().get(partitionColumn.getId()).orElse(null),
                            partitionColumn.getName());
                    NullableValue bindingValue = new NullableValue(partitionColumn.getType(), partitionValue);
                    bindings.put(partitionColumn, bindingValue);
                }
                return bindings;
            });

            if (!dynamicFilterPredicate.isAll() && !dynamicFilterPredicate.equals(pushedDownDynamicFilterPredicate)) {
                if (!partitionMatchesPredicate(
                        identityPartitionColumns,
                        partitionValues,
                        dynamicFilterPredicate)) {
                    continue;
                }
                if (!fileMatchesPredicate(
                        fieldIdToType,
                        dynamicFilterPredicate,
                        scanTask.file().lowerBounds(),
                        scanTask.file().upperBounds(),
                        scanTask.file().nullValueCounts())) {
                    continue;
                }
            }
            if (!partitionMatchesConstraint(identityPartitionColumns, partitionValues, constraint)) {
                continue;
            }
            if (recordScannedFiles) {
                scannedFiles.add(scanTask.file());
            }
            splits.add(icebergSplit);
        }
        return completedFuture(new ConnectorSplitBatch(splits.build(), isFinished()));
    }

    private void finish()
    {
        close();
        this.combinedScanIterable = CloseableIterable.empty();
        this.fileScanIterator = Collections.emptyIterator();
    }

    @Override
    public boolean isFinished()
    {
        return fileScanIterator != null && !fileScanIterator.hasNext();
    }

    @Override
    public Optional<List<Object>> getTableExecuteSplitsInfo()
    {
        checkState(isFinished(), "Split source must be finished before TableExecuteSplitsInfo is read");
        if (!recordScannedFiles) {
            return Optional.empty();
        }
        return Optional.of(ImmutableList.copyOf(scannedFiles.build()));
    }

    @Override
    public void close()
    {
        if (combinedScanIterable != null) {
            try {
                combinedScanIterable.close();
            }
            catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    @VisibleForTesting
    static boolean fileMatchesPredicate(
            Map<Integer, Type.PrimitiveType> primitiveTypeForFieldId,
            TupleDomain<IcebergColumnHandle> dynamicFilterPredicate,
            @Nullable Map<Integer, ByteBuffer> lowerBounds,
            @Nullable Map<Integer, ByteBuffer> upperBounds,
            @Nullable Map<Integer, Long> nullValueCounts)
    {
        if (dynamicFilterPredicate.isNone()) {
            return false;
        }
        Map<IcebergColumnHandle, Domain> domains = dynamicFilterPredicate.getDomains().orElseThrow();

        for (Map.Entry<IcebergColumnHandle, Domain> domainEntry : domains.entrySet()) {
            IcebergColumnHandle column = domainEntry.getKey();
            Domain domain = domainEntry.getValue();

            int fieldId = column.getId();
            boolean mayContainNulls;
            if (nullValueCounts == null) {
                mayContainNulls = true;
            }
            else {
                Long nullValueCount = nullValueCounts.get(fieldId);
                mayContainNulls = nullValueCount == null || nullValueCount > 0;
            }
            Type type = primitiveTypeForFieldId.get(fieldId);
            Domain statisticsDomain = domainForStatistics(
                    column.getType(),
                    lowerBounds == null ? null : fromByteBuffer(type, lowerBounds.get(fieldId)),
                    upperBounds == null ? null : fromByteBuffer(type, upperBounds.get(fieldId)),
                    mayContainNulls);
            if (!domain.overlaps(statisticsDomain)) {
                return false;
            }
        }
        return true;
    }

    private static Domain domainForStatistics(
            io.trino.spi.type.Type type,
            @Nullable Object lowerBound,
            @Nullable Object upperBound,
            boolean mayContainNulls)
    {
        Type icebergType = toIcebergType(type);
        if (lowerBound == null && upperBound == null) {
            return Domain.create(ValueSet.all(type), mayContainNulls);
        }

        Range statisticsRange;
        if (lowerBound != null && upperBound != null) {
            statisticsRange = Range.range(
                    type,
                    convertIcebergValueToTrino(icebergType, lowerBound),
                    true,
                    convertIcebergValueToTrino(icebergType, upperBound),
                    true);
        }
        else if (upperBound != null) {
            statisticsRange = Range.lessThanOrEqual(type, convertIcebergValueToTrino(icebergType, upperBound));
        }
        else {
            statisticsRange = Range.greaterThanOrEqual(type, convertIcebergValueToTrino(icebergType, lowerBound));
        }
        return Domain.create(ValueSet.ofRanges(statisticsRange), mayContainNulls);
    }

    static boolean partitionMatchesConstraint(
            Set<IcebergColumnHandle> identityPartitionColumns,
            Supplier<Map<ColumnHandle, NullableValue>> partitionValues,
            Constraint constraint)
    {
        // We use Constraint just to pass functional predicate here from DistributedExecutionPlanner
        verify(constraint.getSummary().isAll());

        if (constraint.predicate().isEmpty() ||
                intersection(constraint.getPredicateColumns().orElseThrow(), identityPartitionColumns).isEmpty()) {
            return true;
        }
        return constraint.predicate().get().test(partitionValues.get());
    }

    @VisibleForTesting
    static boolean partitionMatchesPredicate(
            Set<IcebergColumnHandle> identityPartitionColumns,
            Supplier<Map<ColumnHandle, NullableValue>> partitionValues,
            TupleDomain<IcebergColumnHandle> dynamicFilterPredicate)
    {
        if (dynamicFilterPredicate.isNone()) {
            return false;
        }
        Map<IcebergColumnHandle, Domain> domains = dynamicFilterPredicate.getDomains().orElseThrow();

        for (IcebergColumnHandle partitionColumn : identityPartitionColumns) {
            Domain allowedDomain = domains.get(partitionColumn);
            if (allowedDomain != null) {
                if (!allowedDomain.includesNullableValue(partitionValues.get().get(partitionColumn).getValue())) {
                    return false;
                }
            }
        }
        return true;
    }

    private  IcebergSplit toIcebergSplit(FileScanTask task)
    {
        /**扩展开始,找到文件的block信息，获取hostaddress*/
        //FileSystem fs = hdfsEnvironment.getFileSystem(hdfsContext, path);
        try {
            FileSystem fileSystem =
                    hdfsEnvironment.getFileSystem(new HdfsEnvironment.HdfsContext(session),
                            new Path(task.file().path().toString()));
            BlockLocation[] blockLocations =
                    fileSystem.getFileBlockLocations(new Path(task.file().path().toString()),
                            task.start(),task.length());
            long start = task.start();
            long length = task.length();
            List<HostAddress> hostAddressList = new ArrayList<>();
            for (BlockLocation blockLocation : blockLocations) {
                // clamp the block range
                long blockStart = Math.max(start, blockLocation.getOffset());
                long blockEnd = Math.min(start + length, blockLocation.getOffset() + blockLocation.getLength());
                if (blockStart > blockEnd) {
                    // block is outside split range
                    continue;
                }
                if (blockStart == blockEnd && !(blockStart == start && blockEnd == start + length)) {
                    // skip zero-width block, except in the special circumstance: slice is empty, and the block covers the empty slice interval.
                    continue;
                }
                hostAddressList.addAll(getHostAddresses(blockLocation));
                //blockBuilder.add(new InternalHiveSplit.InternalHiveBlock(blockStart, blockEnd, getHostAddresses(blockLocation)));
            }
            /**扩展结束，赋予hostaddress，让uniformscheduler调度器能够实现本地化调度*/
            return new IcebergSplit(
                    task.file().path().toString(),
                    task.start(),
                    task.length(),
                    task.file().fileSizeInBytes(),
                    IcebergFileFormat.fromIceberg(task.file().format()),
                    /**原始
                     * ImmutableList.of(),修改为hostAddressList
                     */
                    hostAddressList,
                    getPartitionKeys(task));
        } catch (IOException e) {
            e.printStackTrace();
        }

        return null;
    }

    /**扩展开始，添加了必要的方法，session和hdfsEnvironment用来获取文件系统的句柄fileSystem*/
    private    ConnectorSession session;

    public  void setHdfsEnvironment(HdfsEnvironment hdfsEnvironment) {
        this.hdfsEnvironment = hdfsEnvironment;
    }

    private  HdfsEnvironment hdfsEnvironment;

    public  void setConnectorSession(ConnectorSession session) {
        this.session = session;
    }

    private  List<HostAddress> getHostAddresses(BlockLocation blockLocation)
    {
        // Hadoop FileSystem returns "localhost" as a default
        return Arrays.stream(getBlockHosts(blockLocation))
                .map(HostAddress::fromString)
                .filter(address -> !address.getHostText().equals("localhost"))
                .collect(toImmutableList());
    }
    private  String[] getBlockHosts(BlockLocation blockLocation)
    {
        try {
            return blockLocation.getHosts();
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
    /**扩展结束*/
}
