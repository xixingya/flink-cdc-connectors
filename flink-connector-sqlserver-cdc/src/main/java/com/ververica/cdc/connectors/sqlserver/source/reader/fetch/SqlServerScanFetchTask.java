/*
 * Copyright 2023 Ververica Inc.
 *
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

package com.ververica.cdc.connectors.sqlserver.source.reader.fetch;

import com.ververica.cdc.connectors.base.relational.JdbcSourceEventDispatcher;
import com.ververica.cdc.connectors.base.source.meta.split.SnapshotSplit;
import com.ververica.cdc.connectors.base.source.meta.split.SourceSplitBase;
import com.ververica.cdc.connectors.base.source.meta.split.StreamSplit;
import com.ververica.cdc.connectors.base.source.meta.wartermark.WatermarkKind;
import com.ververica.cdc.connectors.base.source.reader.external.FetchTask;
import com.ververica.cdc.connectors.sqlserver.source.offset.LsnOffset;
import com.ververica.cdc.connectors.sqlserver.source.reader.fetch.SqlServerStreamFetchTask.LsnSplitReadTask;
import io.debezium.DebeziumException;
import io.debezium.config.Configuration;
import io.debezium.connector.sqlserver.SqlServerConnection;
import io.debezium.connector.sqlserver.SqlServerConnectorConfig;
import io.debezium.connector.sqlserver.SqlServerDatabaseSchema;
import io.debezium.connector.sqlserver.SqlServerOffsetContext;
import io.debezium.connector.sqlserver.SqlServerPartition;
import io.debezium.heartbeat.Heartbeat;
import io.debezium.pipeline.EventDispatcher;
import io.debezium.pipeline.source.AbstractSnapshotChangeEventSource;
import io.debezium.pipeline.source.spi.ChangeEventSource;
import io.debezium.pipeline.source.spi.SnapshotProgressListener;
import io.debezium.pipeline.spi.ChangeRecordEmitter;
import io.debezium.pipeline.spi.SnapshotResult;
import io.debezium.relational.RelationalSnapshotChangeEventSource;
import io.debezium.relational.SnapshotChangeRecordEmitter;
import io.debezium.relational.Table;
import io.debezium.relational.TableId;
import io.debezium.util.Clock;
import io.debezium.util.ColumnUtils;
import io.debezium.util.Strings;
import io.debezium.util.Threads;
import org.apache.kafka.connect.errors.ConnectException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Map;

import static com.ververica.cdc.connectors.sqlserver.source.utils.SqlServerUtils.buildSplitScanQuery;
import static com.ververica.cdc.connectors.sqlserver.source.utils.SqlServerUtils.currentLsn;
import static com.ververica.cdc.connectors.sqlserver.source.utils.SqlServerUtils.readTableSplitDataStatement;

/** The task to work for fetching data of SqlServer table snapshot split. */
public class SqlServerScanFetchTask implements FetchTask<SourceSplitBase> {

    private final SnapshotSplit split;
    private volatile boolean taskRunning = false;

    private SqlServerSnapshotSplitReadTask snapshotSplitReadTask;

    public SqlServerScanFetchTask(SnapshotSplit split) {
        this.split = split;
    }

    @Override
    public void execute(Context context) throws Exception {
        SqlServerSourceFetchTaskContext sourceFetchContext =
                (SqlServerSourceFetchTaskContext) context;
        taskRunning = true;
        snapshotSplitReadTask =
                new SqlServerSnapshotSplitReadTask(
                        sourceFetchContext.getDbzConnectorConfig(),
                        sourceFetchContext.getOffsetContext(),
                        sourceFetchContext.getSnapshotChangeEventSourceMetrics(),
                        sourceFetchContext.getDatabaseSchema(),
                        sourceFetchContext.getConnection(),
                        sourceFetchContext.getDispatcher(),
                        sourceFetchContext.getSnapshotReceiver(),
                        split);
        SnapshotSplitChangeEventSourceContext changeEventSourceContext =
                new SnapshotSplitChangeEventSourceContext();
        SnapshotResult<SqlServerOffsetContext> snapshotResult =
                snapshotSplitReadTask.execute(
                        changeEventSourceContext,
                        sourceFetchContext.getPartition(),
                        sourceFetchContext.getOffsetContext());

        final StreamSplit backfillBinlogSplit = createBackFillLsnSplit(changeEventSourceContext);
        // optimization that skip the binlog read when the low watermark equals high
        // watermark
        final boolean binlogBackfillRequired =
                backfillBinlogSplit
                        .getEndingOffset()
                        .isAfter(backfillBinlogSplit.getStartingOffset());
        if (!binlogBackfillRequired) {
            dispatchLsnEndEvent(
                    backfillBinlogSplit,
                    sourceFetchContext.getPartition().getSourcePartition(),
                    sourceFetchContext.getDispatcher());
            taskRunning = false;
            return;
        }
        // execute stream read task
        if (snapshotResult.isCompletedOrSkipped()) {
            final SqlServerOffsetContext.Loader loader =
                    new SqlServerOffsetContext.Loader(sourceFetchContext.getDbzConnectorConfig());
            final SqlServerOffsetContext streamOffsetContext =
                    loader.load(backfillBinlogSplit.getStartingOffset().getOffset());

            final LsnSplitReadTask backfillBinlogReadTask =
                    createBackFillLsnSplitReadTask(backfillBinlogSplit, sourceFetchContext);
            backfillBinlogReadTask.execute(
                    new SnapshotBinlogSplitChangeEventSourceContext(),
                    sourceFetchContext.getPartition(),
                    streamOffsetContext);
        } else {
            taskRunning = false;
            throw new IllegalStateException(
                    String.format("Read snapshot for SqlServer split %s fail", split));
        }
    }

    private StreamSplit createBackFillLsnSplit(
            SnapshotSplitChangeEventSourceContext sourceContext) {
        return new StreamSplit(
                split.splitId(),
                sourceContext.getLowWatermark(),
                sourceContext.getHighWatermark(),
                new ArrayList<>(),
                split.getTableSchemas(),
                0);
    }

    private void dispatchLsnEndEvent(
            StreamSplit backFillBinlogSplit,
            Map<String, ?> sourcePartition,
            JdbcSourceEventDispatcher<SqlServerPartition> eventDispatcher)
            throws InterruptedException {
        eventDispatcher.dispatchWatermarkEvent(
                sourcePartition,
                backFillBinlogSplit,
                backFillBinlogSplit.getEndingOffset(),
                WatermarkKind.END);
    }

    private LsnSplitReadTask createBackFillLsnSplitReadTask(
            StreamSplit backfillBinlogSplit, SqlServerSourceFetchTaskContext context) {
        // we should only capture events for the current table,
        // otherwise, we may can't find corresponding schema
        Configuration dezConf =
                context.getSourceConfig()
                        .getDbzConfiguration()
                        .edit()
                        .with("table.include.list", split.getTableId().toString())
                        // Disable heartbeat event in snapshot split fetcher
                        .with(Heartbeat.HEARTBEAT_INTERVAL, 0)
                        .build();
        // task to read binlog and backfill for current split
        return new LsnSplitReadTask(
                context.getDbzConnectorConfig(),
                context.getConnection(),
                context.getMetaDataConnection(),
                context.getDispatcher(),
                context.getErrorHandler(),
                context.getDatabaseSchema(),
                backfillBinlogSplit);
    }

    @Override
    public boolean isRunning() {
        return taskRunning;
    }

    @Override
    public SourceSplitBase getSplit() {
        return split;
    }

    @Override
    public void close() {
        taskRunning = false;
    }

    /** A wrapped task to fetch snapshot split of table. */
    public static class SqlServerSnapshotSplitReadTask
            extends AbstractSnapshotChangeEventSource<SqlServerPartition, SqlServerOffsetContext> {

        private static final Logger LOG =
                LoggerFactory.getLogger(SqlServerSnapshotSplitReadTask.class);

        /** Interval for showing a log statement with the progress while scanning a single table. */
        private static final Duration LOG_INTERVAL = Duration.ofMillis(10_000);

        private final SqlServerConnectorConfig connectorConfig;
        private final SqlServerDatabaseSchema databaseSchema;
        private final SqlServerConnection jdbcConnection;
        private final JdbcSourceEventDispatcher<SqlServerPartition> dispatcher;
        private final Clock clock;
        private final SnapshotSplit snapshotSplit;
        private final SqlServerOffsetContext offsetContext;
        private final SnapshotProgressListener<SqlServerPartition> snapshotProgressListener;
        private final EventDispatcher.SnapshotReceiver<SqlServerPartition> snapshotReceiver;

        public SqlServerSnapshotSplitReadTask(
                SqlServerConnectorConfig connectorConfig,
                SqlServerOffsetContext previousOffset,
                SnapshotProgressListener<SqlServerPartition> snapshotProgressListener,
                SqlServerDatabaseSchema databaseSchema,
                SqlServerConnection jdbcConnection,
                JdbcSourceEventDispatcher<SqlServerPartition> dispatcher,
                EventDispatcher.SnapshotReceiver<SqlServerPartition> snapshotReceiver,
                SnapshotSplit snapshotSplit) {
            super(connectorConfig, snapshotProgressListener);
            this.offsetContext = previousOffset;
            this.connectorConfig = connectorConfig;
            this.databaseSchema = databaseSchema;
            this.jdbcConnection = jdbcConnection;
            this.dispatcher = dispatcher;
            this.clock = Clock.SYSTEM;
            this.snapshotSplit = snapshotSplit;
            this.snapshotProgressListener = snapshotProgressListener;
            this.snapshotReceiver = snapshotReceiver;
        }

        @Override
        public SnapshotResult<SqlServerOffsetContext> execute(
                ChangeEventSourceContext context,
                SqlServerPartition partition,
                SqlServerOffsetContext previousOffset)
                throws InterruptedException {
            SnapshottingTask snapshottingTask = getSnapshottingTask(partition, previousOffset);
            final SqlSeverSnapshotContext ctx;
            try {
                ctx = prepare(partition);
            } catch (Exception e) {
                LOG.error("Failed to initialize snapshot context.", e);
                throw new RuntimeException(e);
            }
            try {
                return doExecute(context, previousOffset, ctx, snapshottingTask);
            } catch (InterruptedException e) {
                LOG.warn("Snapshot was interrupted before completion");
                throw e;
            } catch (Exception t) {
                throw new DebeziumException(t);
            }
        }

        @Override
        protected SnapshotResult<SqlServerOffsetContext> doExecute(
                ChangeEventSourceContext context,
                SqlServerOffsetContext previousOffset,
                SnapshotContext<SqlServerPartition, SqlServerOffsetContext> snapshotContext,
                SnapshottingTask snapshottingTask)
                throws Exception {
            final SqlSeverSnapshotContext ctx = (SqlSeverSnapshotContext) snapshotContext;
            ctx.offset = offsetContext;

            final LsnOffset lowWatermark = currentLsn(jdbcConnection);
            LOG.info(
                    "Snapshot step 1 - Determining low watermark {} for split {}",
                    lowWatermark,
                    snapshotSplit);
            ((SnapshotSplitChangeEventSourceContext) (context)).setLowWatermark(lowWatermark);
            dispatcher.dispatchWatermarkEvent(
                    snapshotContext.partition.getSourcePartition(),
                    snapshotSplit,
                    lowWatermark,
                    WatermarkKind.LOW);

            LOG.info("Snapshot step 2 - Snapshotting data");
            createDataEvents(ctx, snapshotSplit.getTableId());

            final LsnOffset highWatermark = currentLsn(jdbcConnection);
            LOG.info(
                    "Snapshot step 3 - Determining high watermark {} for split {}",
                    highWatermark,
                    snapshotSplit);
            ((SnapshotSplitChangeEventSourceContext) (context)).setHighWatermark(highWatermark);
            dispatcher.dispatchWatermarkEvent(
                    snapshotContext.partition.getSourcePartition(),
                    snapshotSplit,
                    highWatermark,
                    WatermarkKind.HIGH);
            return SnapshotResult.completed(ctx.offset);
        }

        @Override
        protected SnapshottingTask getSnapshottingTask(
                SqlServerPartition partition, SqlServerOffsetContext previousOffset) {
            return new SnapshottingTask(false, true);
        }

        @Override
        protected SqlSeverSnapshotContext prepare(SqlServerPartition partition) throws Exception {
            return new SqlSeverSnapshotContext(partition);
        }

        private void createDataEvents(SqlSeverSnapshotContext snapshotContext, TableId tableId)
                throws Exception {
            LOG.debug("Snapshotting table {}", tableId);
            createDataEventsForTable(
                    snapshotContext, snapshotReceiver, databaseSchema.tableFor(tableId));
            snapshotReceiver.completeSnapshot();
        }

        /** Dispatches the data change events for the records of a single table. */
        private void createDataEventsForTable(
                SqlSeverSnapshotContext snapshotContext,
                EventDispatcher.SnapshotReceiver<SqlServerPartition> snapshotReceiver,
                Table table)
                throws InterruptedException {

            long exportStart = clock.currentTimeInMillis();
            LOG.info(
                    "Exporting data from split '{}' of table {}",
                    snapshotSplit.splitId(),
                    table.id());

            final String selectSql =
                    buildSplitScanQuery(
                            snapshotSplit.getTableId(),
                            snapshotSplit.getSplitKeyType(),
                            snapshotSplit.getSplitStart() == null,
                            snapshotSplit.getSplitEnd() == null);
            LOG.info(
                    "For split '{}' of table {} using select statement: '{}'",
                    snapshotSplit.splitId(),
                    table.id(),
                    selectSql);

            try (PreparedStatement selectStatement =
                            readTableSplitDataStatement(
                                    jdbcConnection,
                                    selectSql,
                                    snapshotSplit.getSplitStart() == null,
                                    snapshotSplit.getSplitEnd() == null,
                                    snapshotSplit.getSplitStart(),
                                    snapshotSplit.getSplitEnd(),
                                    snapshotSplit.getSplitKeyType().getFieldCount(),
                                    connectorConfig.getQueryFetchSize());
                    ResultSet rs = selectStatement.executeQuery()) {

                ColumnUtils.ColumnArray columnArray = ColumnUtils.toArray(rs, table);
                long rows = 0;
                Threads.Timer logTimer = getTableScanLogTimer();

                while (rs.next()) {
                    rows++;
                    final Object[] row =
                            jdbcConnection.rowToArray(table, databaseSchema, rs, columnArray);
                    if (logTimer.expired()) {
                        long stop = clock.currentTimeInMillis();
                        LOG.info(
                                "Exported {} records for split '{}' after {}",
                                rows,
                                snapshotSplit.splitId(),
                                Strings.duration(stop - exportStart));
                        snapshotProgressListener.rowsScanned(
                                snapshotContext.partition, table.id(), rows);
                        logTimer = getTableScanLogTimer();
                    }
                    dispatcher.dispatchSnapshotEvent(
                            snapshotContext.partition,
                            table.id(),
                            getChangeRecordEmitter(snapshotContext, table.id(), row),
                            snapshotReceiver);
                }
                LOG.info(
                        "Finished exporting {} records for split '{}', total duration '{}'",
                        rows,
                        snapshotSplit.splitId(),
                        Strings.duration(clock.currentTimeInMillis() - exportStart));
            } catch (SQLException e) {
                throw new ConnectException("Snapshotting of table " + table.id() + " failed", e);
            }
        }

        protected ChangeRecordEmitter<SqlServerPartition> getChangeRecordEmitter(
                SqlSeverSnapshotContext snapshotContext, TableId tableId, Object[] row) {
            snapshotContext.offset.event(tableId, clock.currentTime());
            return new SnapshotChangeRecordEmitter<>(
                    snapshotContext.partition, snapshotContext.offset, row, clock);
        }

        private Threads.Timer getTableScanLogTimer() {
            return Threads.timer(clock, LOG_INTERVAL);
        }

        private static class SqlSeverSnapshotContext
                extends RelationalSnapshotChangeEventSource.RelationalSnapshotContext<
                        SqlServerPartition, SqlServerOffsetContext> {

            public SqlSeverSnapshotContext(SqlServerPartition partition) throws SQLException {
                super(partition, "");
            }
        }
    }

    /**
     * {@link ChangeEventSource.ChangeEventSourceContext} implementation that keeps low/high
     * watermark for each {@link SnapshotSplit}.
     */
    public static class SnapshotSplitChangeEventSourceContext
            implements ChangeEventSource.ChangeEventSourceContext {

        private LsnOffset lowWatermark;
        private LsnOffset highWatermark;

        public LsnOffset getLowWatermark() {
            return lowWatermark;
        }

        public void setLowWatermark(LsnOffset lowWatermark) {
            this.lowWatermark = lowWatermark;
        }

        public LsnOffset getHighWatermark() {
            return highWatermark;
        }

        public void setHighWatermark(LsnOffset highWatermark) {
            this.highWatermark = highWatermark;
        }

        @Override
        public boolean isRunning() {
            return lowWatermark != null && highWatermark != null;
        }
    }

    /**
     * The {@link ChangeEventSource.ChangeEventSourceContext} implementation for bounded stream task
     * of a snapshot split task.
     */
    public class SnapshotBinlogSplitChangeEventSourceContext
            implements ChangeEventSource.ChangeEventSourceContext {

        public void finished() {
            taskRunning = false;
        }

        @Override
        public boolean isRunning() {
            return taskRunning;
        }
    }
}
