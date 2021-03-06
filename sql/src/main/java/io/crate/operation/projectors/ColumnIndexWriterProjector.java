/*
 * Licensed to CRATE Technology GmbH ("Crate") under one or more contributor
 * license agreements.  See the NOTICE file distributed with this work for
 * additional information regarding copyright ownership.  Crate licenses
 * this file to you under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.  You may
 * obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 *
 * However, if you have executed another commercial license agreement
 * with Crate these terms will supersede the license and you may use the
 * software solely pursuant to the terms of the relevant commercial agreement.
 */

package io.crate.operation.projectors;

import com.google.common.base.MoreObjects;
import com.google.common.util.concurrent.Futures;
import io.crate.analyze.symbol.Assignments;
import io.crate.analyze.symbol.Symbol;
import io.crate.core.collections.Row;
import io.crate.executor.transport.ShardUpsertRequest;
import io.crate.executor.transport.TransportActionProvider;
import io.crate.metadata.ColumnIdent;
import io.crate.metadata.Functions;
import io.crate.metadata.Reference;
import io.crate.metadata.settings.CrateSettings;
import io.crate.operation.Input;
import io.crate.operation.InputRow;
import io.crate.operation.collect.CollectExpression;
import io.crate.operation.collect.RowShardResolver;
import org.elasticsearch.action.bulk.BulkRetryCoordinatorPool;
import org.elasticsearch.action.bulk.BulkShardProcessor;
import org.elasticsearch.cluster.ClusterService;
import org.elasticsearch.cluster.metadata.IndexNameExpressionResolver;
import org.elasticsearch.common.collect.Tuple;
import org.elasticsearch.common.settings.Settings;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

public class ColumnIndexWriterProjector extends AbstractProjector {

    private final Iterable<? extends CollectExpression<Row, ?>> collectExpressions;

    private final RowShardResolver rowShardResolver;
    private final Supplier<String> indexNameResolver;
    private final Symbol[] assignments;
    private final InputRow insertValues;
    private BulkShardProcessor<ShardUpsertRequest> bulkShardProcessor;
    private final AtomicBoolean failed = new AtomicBoolean(false);


    protected ColumnIndexWriterProjector(ClusterService clusterService,
                                         Functions functions,
                                         IndexNameExpressionResolver indexNameExpressionResolver,
                                         Settings settings,
                                         Supplier<String> indexNameResolver,
                                         TransportActionProvider transportActionProvider,
                                         BulkRetryCoordinatorPool bulkRetryCoordinatorPool,
                                         List<ColumnIdent> primaryKeyIdents,
                                         List<? extends Symbol> primaryKeySymbols,
                                         @Nullable Symbol routingSymbol,
                                         ColumnIdent clusteredByColumn,
                                         List<Reference> columnReferences,
                                         List<Input<?>> insertInputs,
                                         Iterable<? extends CollectExpression<Row, ?>> collectExpressions,
                                         @Nullable Map<Reference, Symbol> updateAssignments,
                                         @Nullable Integer bulkActions,
                                         boolean autoCreateIndices,
                                         UUID jobId) {
        this.indexNameResolver = indexNameResolver;
        this.collectExpressions = collectExpressions;
        rowShardResolver = new RowShardResolver(functions, primaryKeyIdents, primaryKeySymbols, clusteredByColumn, routingSymbol);
        assert columnReferences.size() == insertInputs.size()
            : "number of insert inputs must be equal to the number of columns";

        String[] updateColumnNames;
        if (updateAssignments == null) {
            updateColumnNames = null;
            assignments = null;
        } else {
            Tuple<String[], Symbol[]> convert = Assignments.convert(updateAssignments);
            updateColumnNames = convert.v1();
            assignments = convert.v2();
        }
        ShardUpsertRequest.Builder builder = new ShardUpsertRequest.Builder(
            CrateSettings.BULK_REQUEST_TIMEOUT.extractTimeValue(settings),
            false, // overwriteDuplicates
            true, // continueOnErrors
            updateColumnNames,
            columnReferences.toArray(new Reference[columnReferences.size()]),
            jobId);

        insertValues = new InputRow(insertInputs);
        bulkShardProcessor = new BulkShardProcessor<>(
            clusterService,
            transportActionProvider.transportBulkCreateIndicesAction(),
            indexNameExpressionResolver,
            settings,
            bulkRetryCoordinatorPool,
            autoCreateIndices,
            MoreObjects.firstNonNull(bulkActions, 100),
            builder,
            transportActionProvider.transportShardUpsertActionDelegate(),
            jobId
        );
    }

    @Override
    public void downstream(RowReceiver rowReceiver) {
        super.downstream(rowReceiver);
        Futures.addCallback(bulkShardProcessor.result(), new BulkProcessorFutureCallback(failed, rowReceiver));
    }

    @Override
    public Result setNextRow(Row row) {
        for (CollectExpression<Row, ?> collectExpression : collectExpressions) {
            collectExpression.setNextRow(row);
        }
        rowShardResolver.setNextRow(row);
        ShardUpsertRequest.Item item = new ShardUpsertRequest.Item(
            rowShardResolver.id(), assignments, insertValues.materialize(), null);
        if (bulkShardProcessor.add(indexNameResolver.get(), item, rowShardResolver.routing())) {
            return Result.CONTINUE;
        }
        return Result.STOP;
    }

    @Override
    public void finish(RepeatHandle repeatHandle) {
        bulkShardProcessor.close();
    }

    @Override
    public void kill(Throwable throwable) {
        super.kill(throwable);
        failed.set(true);
        bulkShardProcessor.kill(throwable);
    }

    @Override
    public void fail(Throwable throwable) {
        failed.set(true);
        downstream.fail(throwable);
        bulkShardProcessor.kill(throwable);
    }
}
