/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.ingest;

import org.apache.lucene.util.IOUtils;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.admin.indices.create.CreateIndexRequest;
import org.elasticsearch.action.admin.indices.create.CreateIndexResponse;
import org.elasticsearch.action.delete.DeleteRequest;
import org.elasticsearch.action.delete.DeleteResponse;
import org.elasticsearch.action.get.GetRequest;
import org.elasticsearch.action.get.GetResponse;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.index.IndexResponse;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.support.IndicesOptions;
import org.elasticsearch.client.Client;
import org.elasticsearch.cluster.ClusterService;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.metadata.IndexMetaData;
import org.elasticsearch.common.SearchScrollIterator;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.component.AbstractComponent;
import org.elasticsearch.common.regex.Regex;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.elasticsearch.common.xcontent.XContentHelper;
import org.elasticsearch.common.xcontent.support.XContentMapValues;
import org.elasticsearch.env.Environment;
import org.elasticsearch.index.IndexNotFoundException;
import org.elasticsearch.action.ingest.DeletePipelineRequest;
import org.elasticsearch.action.ingest.PutPipelineRequest;
import org.elasticsearch.action.ingest.ReloadPipelinesAction;
import org.elasticsearch.ingest.core.Pipeline;
import org.elasticsearch.ingest.core.Processor;
import org.elasticsearch.ingest.core.TemplateService;
import org.elasticsearch.script.ScriptService;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.elasticsearch.search.sort.SortOrder;
import org.elasticsearch.transport.TransportService;

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

public class PipelineStore extends AbstractComponent implements Closeable {

    public final static String INDEX = ".ingest";
    public final static String TYPE = "pipeline";

    final static Settings INGEST_INDEX_SETTING = Settings.builder()
        .put(IndexMetaData.SETTING_NUMBER_OF_SHARDS, 1)
        .put(IndexMetaData.SETTING_NUMBER_OF_REPLICAS, 1)
        .put("index.mapper.dynamic", false)
        .build();

    final static String PIPELINE_MAPPING;

    static {
        try {
            PIPELINE_MAPPING = XContentFactory.jsonBuilder().startObject()
                .field("dynamic", "strict")
                .startObject("_all")
                    .field("enabled", false)
                .endObject()
                .startObject("properties")
                    .startObject("processors")
                        .field("type", "object")
                        .field("enabled", false)
                        .field("dynamic", true)
                    .endObject()
                    .startObject("on_failure")
                        .field("type", "object")
                        .field("enabled", false)
                        .field("dynamic", true)
                    .endObject()
                    .startObject("description")
                        .field("type", "string")
                    .endObject()
                .endObject()
                .endObject().string();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private Client client;
    private final TimeValue scrollTimeout;
    private final ClusterService clusterService;
    private final ReloadPipelinesAction reloadPipelinesAction;
    private final Pipeline.Factory factory = new Pipeline.Factory();
    private Map<String, Processor.Factory> processorFactoryRegistry;

    private volatile boolean started = false;
    private volatile Map<String, PipelineDefinition> pipelines = new HashMap<>();

    public PipelineStore(Settings settings, ClusterService clusterService, TransportService transportService) {
        super(settings);
        this.clusterService = clusterService;
        this.scrollTimeout = settings.getAsTime("ingest.pipeline.store.scroll.timeout", TimeValue.timeValueSeconds(30));
        this.reloadPipelinesAction = new ReloadPipelinesAction(settings, this, clusterService, transportService);
    }

    public void setClient(Client client) {
        this.client = client;
    }

    public void buildProcessorFactoryRegistry(ProcessorsRegistry processorsRegistry, Environment environment, ScriptService scriptService) {
        Map<String, Processor.Factory> processorFactories = new HashMap<>();
        TemplateService templateService = new InternalTemplateService(scriptService);
        for (Map.Entry<String, BiFunction<Environment, TemplateService, Processor.Factory<?>>> entry : processorsRegistry.entrySet()) {
            Processor.Factory processorFactory = entry.getValue().apply(environment, templateService);
            processorFactories.put(entry.getKey(), processorFactory);
        }
        this.processorFactoryRegistry = Collections.unmodifiableMap(processorFactories);
    }

    @Override
    public void close() throws IOException {
        stop("closing");
        // TODO: When org.elasticsearch.node.Node can close Closable instances we should try to remove this code,
        // since any wired closable should be able to close itself
        List<Closeable> closeables = new ArrayList<>();
        for (Processor.Factory factory : processorFactoryRegistry.values()) {
            if (factory instanceof Closeable) {
                closeables.add((Closeable) factory);
            }
        }
        IOUtils.close(closeables);
    }

    /**
     * Deletes the pipeline specified by id in the request.
     */
    public void delete(DeletePipelineRequest request, ActionListener<DeleteResponse> listener) {
        ensureReady();

        DeleteRequest deleteRequest = new DeleteRequest(request);
        deleteRequest.index(PipelineStore.INDEX);
        deleteRequest.type(PipelineStore.TYPE);
        deleteRequest.id(request.id());
        deleteRequest.refresh(true);
        client.delete(deleteRequest, handleWriteResponseAndReloadPipelines(listener));
    }

    /**
     * Stores the specified pipeline definition in the request.
     *
     * @throws IllegalArgumentException If the pipeline holds incorrect configuration
     */
    public void put(PutPipelineRequest request, ActionListener<IndexResponse> listener) throws IllegalArgumentException {
        ensureReady();

        try {
            // validates the pipeline and processor configuration:
            Map<String, Object> pipelineConfig = XContentHelper.convertToMap(request.source(), false).v2();
            constructPipeline(request.id(), pipelineConfig);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid pipeline configuration", e);
        }

        ClusterState state = clusterService.state();
        if (isIngestIndexPresent(state)) {
            innerPut(request, listener);
        } else {
            CreateIndexRequest createIndexRequest = new CreateIndexRequest(INDEX);
            createIndexRequest.settings(INGEST_INDEX_SETTING);
            createIndexRequest.mapping(TYPE, PIPELINE_MAPPING);
            client.admin().indices().create(createIndexRequest, new ActionListener<CreateIndexResponse>() {
                @Override
                public void onResponse(CreateIndexResponse createIndexResponse) {
                    innerPut(request, listener);
                }

                @Override
                public void onFailure(Throwable e) {
                    listener.onFailure(e);
                }
            });
        }
    }

    private void innerPut(PutPipelineRequest request, ActionListener<IndexResponse> listener) {
        IndexRequest indexRequest = new IndexRequest(request);
        indexRequest.index(PipelineStore.INDEX);
        indexRequest.type(PipelineStore.TYPE);
        indexRequest.id(request.id());
        indexRequest.source(request.source());
        indexRequest.refresh(true);
        client.index(indexRequest, handleWriteResponseAndReloadPipelines(listener));
    }

    /**
     * Returns the pipeline by the specified id
     */
    public Pipeline get(String id) {
        ensureReady();

        PipelineDefinition ref = pipelines.get(id);
        if (ref != null) {
            return ref.getPipeline();
        } else {
            return null;
        }
    }

    public Map<String, Processor.Factory> getProcessorFactoryRegistry() {
        return processorFactoryRegistry;
    }

    public List<PipelineDefinition> getReference(String... ids) {
        ensureReady();

        List<PipelineDefinition> result = new ArrayList<>(ids.length);
        for (String id : ids) {
            if (Regex.isSimpleMatchPattern(id)) {
                for (Map.Entry<String, PipelineDefinition> entry : pipelines.entrySet()) {
                    if (Regex.simpleMatch(id, entry.getKey())) {
                        result.add(entry.getValue());
                    }
                }
            } else {
                PipelineDefinition reference = pipelines.get(id);
                if (reference != null) {
                    result.add(reference);
                }
            }
        }
        return result;
    }

    public synchronized void updatePipelines() throws Exception {
        // note: this process isn't fast or smart, but the idea is that there will not be many pipelines,
        // so for that reason the goal is to keep the update logic simple.

        int changed = 0;
        Map<String, PipelineDefinition> newPipelines = new HashMap<>(pipelines);
        for (SearchHit hit : readAllPipelines()) {
            String pipelineId = hit.getId();
            BytesReference pipelineSource = hit.getSourceRef();
            PipelineDefinition current = newPipelines.get(pipelineId);
            if (current != null) {
                // If we first read from a primary shard copy and then from a replica copy,
                // and a write did not yet make it into the replica shard
                // then the source is not equal but we don't update because the current pipeline is the latest:
                if (current.getVersion() > hit.getVersion()) {
                    continue;
                }
                if (current.getSource().equals(pipelineSource)) {
                    continue;
                }
            }

            changed++;
            Pipeline pipeline = constructPipeline(hit.getId(), hit.sourceAsMap());
            newPipelines.put(pipelineId, new PipelineDefinition(pipeline, hit.getVersion(), pipelineSource));
        }

        int removed = 0;
        for (String existingPipelineId : pipelines.keySet()) {
            if (pipelineExists(existingPipelineId) == false) {
                newPipelines.remove(existingPipelineId);
                removed++;
            }
        }

        if (changed != 0 || removed != 0) {
            logger.debug("adding or updating [{}] pipelines and [{}] pipelines removed", changed, removed);
            pipelines = newPipelines;
        } else {
            logger.debug("no pipelines changes detected");
        }
    }

    private Pipeline constructPipeline(String id, Map<String, Object> config) throws Exception {
        return factory.create(id, config, processorFactoryRegistry);
    }

    boolean pipelineExists(String pipelineId) {
        GetRequest request = new GetRequest(PipelineStore.INDEX, PipelineStore.TYPE, pipelineId);
        try {
            GetResponse response = client.get(request).actionGet();
            return response.isExists();
        } catch (IndexNotFoundException e) {
            // the ingest index doesn't exist, so the pipeline doesn't either:
            return false;
        }
    }

    /**
     * @param clusterState The cluster just to check whether the ingest index exists and the state of the ingest index
     * @throws IllegalStateException If the ingest template exists, but is in an invalid state
     * @return <code>true</code> when the ingest index exists and has the expected settings and mappings or returns
     * <code>false</code> when the ingest index doesn't exists and needs to be created.
     */
    boolean isIngestIndexPresent(ClusterState clusterState) throws IllegalStateException {
        if (clusterState.getMetaData().hasIndex(INDEX)) {
            IndexMetaData indexMetaData = clusterState.getMetaData().index(INDEX);
            Settings indexSettings = indexMetaData.getSettings();
            int numberOfShards = indexSettings.getAsInt(IndexMetaData.SETTING_NUMBER_OF_SHARDS, -1);
            if (numberOfShards != 1) {
                throw new IllegalStateException("illegal ingest index setting, [" + IndexMetaData.SETTING_NUMBER_OF_SHARDS + "] setting is [" + numberOfShards + "] while [1] is expected");
            }
            int numberOfReplicas = indexSettings.getAsInt(IndexMetaData.SETTING_NUMBER_OF_REPLICAS, -1);
            if (numberOfReplicas != 1) {
                throw new IllegalStateException("illegal ingest index setting, [" + IndexMetaData.SETTING_NUMBER_OF_REPLICAS + "] setting is [" + numberOfReplicas + "] while [1] is expected");
            }
            boolean dynamicMappings = indexSettings.getAsBoolean("index.mapper.dynamic", true);
            if (dynamicMappings != false) {
                throw new IllegalStateException("illegal ingest index setting, [index.mapper.dynamic] setting is [" + dynamicMappings + "] while [false] is expected");
            }

            if (indexMetaData.getMappings().size() != 1 && indexMetaData.getMappings().containsKey(TYPE) == false) {
                throw new IllegalStateException("illegal ingest mappings, only [" + TYPE + "] mapping is allowed to exist in the " + INDEX +" index");
            }

            try {
                Map<String, Object> pipelineMapping = indexMetaData.getMappings().get(TYPE).getSourceAsMap();
                String dynamicMapping = (String) XContentMapValues.extractValue("dynamic", pipelineMapping);
                if ("strict".equals(dynamicMapping) == false) {
                    throw new IllegalStateException("illegal ingest mapping, pipeline mapping must be strict");
                }
                Boolean allEnabled = (Boolean) XContentMapValues.extractValue("_all.enabled", pipelineMapping);
                if (Boolean.FALSE.equals(allEnabled) == false) {
                    throw new IllegalStateException("illegal ingest mapping, _all field is enabled");
                }

                String processorsType = (String) XContentMapValues.extractValue("properties.processors.type", pipelineMapping);
                if ("object".equals(processorsType) == false) {
                    throw new IllegalStateException("illegal ingest mapping, processors field's type is [" + processorsType + "] while [object] is expected");
                }

                Boolean processorsEnabled = (Boolean) XContentMapValues.extractValue("properties.processors.enabled", pipelineMapping);
                if (Boolean.FALSE.equals(processorsEnabled) == false) {
                    throw new IllegalStateException("illegal ingest mapping, processors field enabled option is [true] while [false] is expected");
                }

                Boolean processorsDynamic = (Boolean) XContentMapValues.extractValue("properties.processors.dynamic", pipelineMapping);
                if (Boolean.TRUE.equals(processorsDynamic) == false) {
                    throw new IllegalStateException("illegal ingest mapping, processors field dynamic option is [false] while [true] is expected");
                }

                String onFailureType = (String) XContentMapValues.extractValue("properties.on_failure.type", pipelineMapping);
                if ("object".equals(onFailureType) == false) {
                    throw new IllegalStateException("illegal ingest mapping, on_failure field type option is [" + onFailureType + "] while [object] is expected");
                }

                Boolean onFailureEnabled = (Boolean) XContentMapValues.extractValue("properties.on_failure.enabled", pipelineMapping);
                if (Boolean.FALSE.equals(onFailureEnabled) == false) {
                    throw new IllegalStateException("illegal ingest mapping, on_failure field enabled option is [true] while [false] is expected");
                }

                Boolean onFailureDynamic = (Boolean) XContentMapValues.extractValue("properties.on_failure.dynamic", pipelineMapping);
                if (Boolean.TRUE.equals(onFailureDynamic) == false) {
                    throw new IllegalStateException("illegal ingest mapping, on_failure field dynamic option is [false] while [true] is expected");
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            return true;
        } else {
            return false;
        }
    }


    synchronized void start() throws Exception {
        if (started) {
            logger.debug("Pipeline already started");
        } else {
            updatePipelines();
            started = true;
            logger.debug("Pipeline store started with [{}] pipelines", pipelines.size());
        }
    }

    synchronized void stop(String reason) {
        if (started) {
            started = false;
            pipelines = new HashMap<>();
            logger.debug("Pipeline store stopped, reason [{}]", reason);
        } else {
            logger.debug("Pipeline alreadt stopped");
        }
    }

    public boolean isStarted() {
        return started;
    }

    private Iterable<SearchHit> readAllPipelines() {
        // TODO: the search should be replaced with an ingest API when it is available
        SearchSourceBuilder sourceBuilder = new SearchSourceBuilder();
        sourceBuilder.version(true);
        sourceBuilder.sort("_doc", SortOrder.ASC);
        SearchRequest searchRequest = new SearchRequest(PipelineStore.INDEX);
        searchRequest.source(sourceBuilder);
        searchRequest.indicesOptions(IndicesOptions.lenientExpandOpen());
        return SearchScrollIterator.createIterator(client, scrollTimeout, searchRequest);
    }

    private void ensureReady() {
        if (started == false) {
            throw new IllegalStateException("pipeline store isn't ready yet");
        }
    }

    @SuppressWarnings("unchecked")
    private <T> ActionListener<T> handleWriteResponseAndReloadPipelines(ActionListener<T> listener) {
        return new ActionListener<T>() {
            @Override
            public void onResponse(T result) {
                try {
                    reloadPipelinesAction.reloadPipelinesOnAllNodes(reloadResult -> listener.onResponse(result));
                } catch (Throwable e) {
                    listener.onFailure(e);
                }
            }

            @Override
            public void onFailure(Throwable e) {
                listener.onFailure(e);
            }
        };
    }

}