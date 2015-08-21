/**
 * Licensed to DigitalPebble Ltd under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * DigitalPebble licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.digitalpebble.stormcrawler.aws.bolt;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import backtype.storm.Config;
import backtype.storm.Constants;
import backtype.storm.metric.api.MultiCountMetric;
import backtype.storm.task.OutputCollector;
import backtype.storm.task.TopologyContext;
import backtype.storm.topology.OutputFieldsDeclarer;
import backtype.storm.tuple.Tuple;

import com.amazonaws.regions.RegionUtils;
import com.amazonaws.services.cloudsearchdomain.AmazonCloudSearchDomainClient;
import com.amazonaws.services.cloudsearchdomain.model.ContentType;
import com.amazonaws.services.cloudsearchdomain.model.DocumentServiceWarning;
import com.amazonaws.services.cloudsearchdomain.model.UploadDocumentsRequest;
import com.amazonaws.services.cloudsearchdomain.model.UploadDocumentsResult;
import com.amazonaws.services.cloudsearchv2.AmazonCloudSearchClient;
import com.amazonaws.services.cloudsearchv2.model.DescribeDomainsRequest;
import com.amazonaws.services.cloudsearchv2.model.DescribeDomainsResult;
import com.amazonaws.services.cloudsearchv2.model.DescribeIndexFieldsRequest;
import com.amazonaws.services.cloudsearchv2.model.DescribeIndexFieldsResult;
import com.amazonaws.services.cloudsearchv2.model.DomainStatus;
import com.amazonaws.services.cloudsearchv2.model.IndexFieldStatus;
import com.amazonaws.util.json.JSONException;
import com.amazonaws.util.json.JSONObject;
import com.digitalpebble.storm.crawler.Metadata;
import com.digitalpebble.storm.crawler.indexing.AbstractIndexerBolt;
import com.digitalpebble.storm.crawler.util.ConfUtils;

/**
 * Writes documents to CloudSearch.
 */
@SuppressWarnings("serial")
public class CloudSearchIndexerBolt extends AbstractIndexerBolt {

    public static final Logger LOG = LoggerFactory
            .getLogger(CloudSearchIndexerBolt.class);

    private static final int MAX_SIZE_BATCH_BYTES = 5242880;
    private static final int MAX_SIZE_DOC_BYTES = 1048576;

    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat(
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");

    private AmazonCloudSearchDomainClient client;

    private int maxDocsInBatch = -1;

    private StringBuffer buffer;

    private int numDocsInBatch = 0;

    /** Max amount of time wait before indexing **/
    private int maxTimeBuffered = 10;

    private boolean dumpBatchFilesToTemp = false;

    private OutputCollector _collector;

    private MultiCountMetric eventCounter;

    private Map<String, String> csfields = new HashMap<String, String>();

    private long timeLastBatchSent = System.currentTimeMillis();

    private List<Tuple> unacked = new ArrayList<Tuple>();

    @SuppressWarnings({ "rawtypes", "unchecked" })
    @Override
    public void prepare(Map conf, TopologyContext context,
            OutputCollector collector) {
        super.prepare(conf, context, collector);
        _collector = collector;

        this.eventCounter = context.registerMetric("CloudSearchIndexer",
                new MultiCountMetric(), 10);

        maxTimeBuffered = ConfUtils.getInt(conf,
                CloudSearchConstants.MAX_TIME_BUFFERED, 10);

        maxDocsInBatch = ConfUtils.getInt(conf,
                CloudSearchConstants.MAX_DOCS_BATCH, -1);

        buffer = new StringBuffer(MAX_SIZE_BATCH_BYTES).append('[');

        dumpBatchFilesToTemp = ConfUtils.getBoolean(conf,
                "cloudsearch.batch.dump", false);

        if (dumpBatchFilesToTemp) {
            // only dumping to local file
            // no more config required
            return;
        }

        String endpoint = ConfUtils.getString(conf, "cloudsearch.endpoint");

        if (StringUtils.isBlank(endpoint)) {
            String message = "Missing CloudSearch endpoint";
            LOG.error(message);
            throw new RuntimeException(message);
        }

        String regionName = ConfUtils.getString(conf,
                CloudSearchConstants.REGION);

        AmazonCloudSearchClient cl = new AmazonCloudSearchClient();
        if (StringUtils.isNotBlank(regionName)) {
            cl.setRegion(RegionUtils.getRegion(regionName));
        }

        String domainName = null;

        // retrieve the domain name
        DescribeDomainsResult domains = cl
                .describeDomains(new DescribeDomainsRequest());

        Iterator<DomainStatus> dsiter = domains.getDomainStatusList()
                .iterator();
        while (dsiter.hasNext()) {
            DomainStatus ds = dsiter.next();
            if (ds.getDocService().getEndpoint().equals(endpoint)) {
                domainName = ds.getDomainName();
                break;
            }
        }
        // check domain name
        if (StringUtils.isBlank(domainName)) {
            throw new RuntimeException(
                    "No domain name found for CloudSearch endpoint");
        }

        DescribeIndexFieldsResult indexDescription = cl
                .describeIndexFields(new DescribeIndexFieldsRequest()
                        .withDomainName(domainName));
        for (IndexFieldStatus ifs : indexDescription.getIndexFields()) {
            String indexname = ifs.getOptions().getIndexFieldName();
            String indextype = ifs.getOptions().getIndexFieldType();
            LOG.info("CloudSearch index name {} of type {}", indexname,
                    indextype);
            csfields.put(indexname, indextype);
        }

        client = new AmazonCloudSearchDomainClient();
        client.setEndpoint(endpoint);
    }

    @Override
    public void execute(Tuple tuple) {

        if (isTickTuple(tuple)) {
            // check when we last sent a batch
            long now = System.currentTimeMillis();
            long gap = now - timeLastBatchSent;
            if (gap >= maxTimeBuffered * 1000) {
                sendBatch();
            }
            return;
        }

        String url = valueForURL(tuple);
        Metadata metadata = (Metadata) tuple.getValueByField("metadata");
        String text = tuple.getStringByField("text");

        boolean keep = filterDocument(metadata);
        if (!keep) {
            eventCounter.scope("Filtered").incrBy(1);
            _collector.ack(tuple);
            return;
        }

        try {
            JSONObject doc_builder = new JSONObject();

            doc_builder.put("type", "add");

            // generate the id from the url
            String ID = CloudSearchUtils.getID(url);
            doc_builder.put("id", ID);

            JSONObject fields = new JSONObject();

            // which metadata to include as fields
            Map<String, String[]> keyVals = filterMetadata(metadata);

            for (final Entry<String, String[]> e : keyVals.entrySet()) {
                String fieldname = CloudSearchUtils.cleanFieldName(e.getKey());
                String type = csfields.get(fieldname);

                // undefined in index
                if (type == null && !this.dumpBatchFilesToTemp) {
                    LOG.info(
                            "Field {} not defined in CloudSearch domain for {} - skipping.",
                            fieldname, url);
                    continue;
                }

                String[] values = e.getValue();

                // check that there aren't multiple values if not defined so in
                // the index
                if (values.length > 1
                        && !StringUtils.containsIgnoreCase(type, "-array")) {
                    LOG.info(
                            "{} values found for field {} of type {} - keeping only the first one. {}",
                            values.length, fieldname, type, url);
                    values = new String[] { values[0] };
                }

                // write the values
                for (String value : values) {
                    // Check that the date format is correct
                    if (StringUtils.containsIgnoreCase(type, "date")) {
                        try {
                            DATE_FORMAT.parse(value);
                        } catch (ParseException pe) {
                            LOG.info("Unparsable date {}", value);
                            continue;
                        }
                    }
                    // normalise strings
                    else {
                        value = CloudSearchUtils.stripNonCharCodepoints(value);
                    }

                    fields.accumulate(fieldname, value);
                }
            }

            // include the url ?
            String fieldNameForURL = fieldNameForURL();
            if (StringUtils.isNotBlank(fieldNameForURL)) {
                fieldNameForURL = CloudSearchUtils
                        .cleanFieldName(fieldNameForURL);
                if (this.dumpBatchFilesToTemp
                        || csfields.get(fieldNameForURL) != null) {
                    url = CloudSearchUtils.stripNonCharCodepoints(url);
                    fields.put(fieldNameForURL, url);
                }
            }

            // include the text ?
            String fieldNameForText = fieldNameForText();
            if (StringUtils.isNotBlank(fieldNameForText)) {
                fieldNameForText = CloudSearchUtils
                        .cleanFieldName(fieldNameForText);
                if (this.dumpBatchFilesToTemp
                        || csfields.get(fieldNameForText) != null) {
                    text = CloudSearchUtils.stripNonCharCodepoints(text);
                    fields.put(fieldNameForText, text);
                }
            }

            doc_builder.put("fields", fields);

            addToBatch(doc_builder.toString(2), url, tuple);

        } catch (JSONException e) {
            LOG.error("Exception caught while building JSON object", e);
            // resending would produce the same results no point in retrying
            _collector.ack(tuple);
        }
    }

    private void addToBatch(String currentDoc, String url, Tuple tuple) {
        int currentDocLength = currentDoc.getBytes(StandardCharsets.UTF_8).length;

        // check that the doc is not too large -> skip it if it does
        if (currentDocLength > MAX_SIZE_DOC_BYTES) {
            LOG.error("Doc too large. currentDoc.length {} : {}",
                    currentDocLength, url);
            return;
        }

        int currentBufferLength = buffer.toString().getBytes(
                StandardCharsets.UTF_8).length;

        LOG.debug("currentDoc.length {}, buffer length {}", currentDocLength,
                currentBufferLength);

        // can add it to the buffer without overflowing?
        if (currentDocLength + 2 + currentBufferLength < MAX_SIZE_BATCH_BYTES) {
            if (numDocsInBatch != 0)
                buffer.append(',');
            buffer.append(currentDoc);
            this.unacked.add(tuple);
            numDocsInBatch++;
        }
        // flush the previous batch and create a new one with this doc
        else {
            sendBatch();
            buffer.append(currentDoc);
            this.unacked.add(tuple);
            numDocsInBatch++;
        }

        // have we reached the max number of docs in a batch after adding
        // this doc?
        if (maxDocsInBatch > 0 && numDocsInBatch == maxDocsInBatch) {
            sendBatch();
        }
    }

    public void sendBatch() {

        timeLastBatchSent = System.currentTimeMillis();

        // nothing to do
        if (numDocsInBatch == 0) {
            return;
        }

        // close the array
        buffer.append(']');

        LOG.info("Sending {} docs to CloudSearch", numDocsInBatch);

        byte[] bb = buffer.toString().getBytes(StandardCharsets.UTF_8);

        if (dumpBatchFilesToTemp) {
            try {
                File temp = File.createTempFile("CloudSearch_", ".json");
                FileUtils.writeByteArrayToFile(temp, bb);
                LOG.info("Wrote batch file {}", temp.getName());
                // ack the tuples
                for (Tuple t : unacked) {
                    _collector.ack(t);
                }
                unacked.clear();
            } catch (IOException e1) {
                LOG.error("Exception while generating batch file", e1);
                // fail the tuples
                for (Tuple t : unacked) {
                    _collector.fail(t);
                }
                unacked.clear();
            } finally {
                // reset buffer and doc counter
                buffer = new StringBuffer(MAX_SIZE_BATCH_BYTES).append('[');
                numDocsInBatch = 0;
            }
            return;
        }
        // not in debug mode
        try (InputStream inputStream = new ByteArrayInputStream(bb)) {
            UploadDocumentsRequest batch = new UploadDocumentsRequest();
            batch.setContentLength((long) bb.length);
            batch.setContentType(ContentType.Applicationjson);
            batch.setDocuments(inputStream);
            UploadDocumentsResult result = client.uploadDocuments(batch);
            LOG.info(result.getStatus());
            for (DocumentServiceWarning warning : result.getWarnings()) {
                LOG.info(warning.getMessage());
            }
            if (!result.getWarnings().isEmpty()) {
                eventCounter.scope("Warnings").incrBy(
                        result.getWarnings().size());
            }
            eventCounter.scope("Added").incrBy(result.getAdds());
            // ack the tuples
            for (Tuple t : unacked) {
                _collector.ack(t);
            }
            unacked.clear();
        } catch (Exception e) {
            LOG.error("Exception while sending batch", e);
            LOG.error(buffer.toString());
            // fail the tuples
            for (Tuple t : unacked) {
                _collector.fail(t);
            }
            unacked.clear();
        } finally {
            // reset buffer and doc counter
            buffer = new StringBuffer(MAX_SIZE_BATCH_BYTES).append('[');
            numDocsInBatch = 0;
        }
    }

    @Override
    public void declareOutputFields(OutputFieldsDeclarer arg0) {
    }

    @Override
    public void cleanup() {
        // This will flush any unsent documents.
        sendBatch();
        client.shutdown();
    }

    private boolean isTickTuple(Tuple tuple) {
        String sourceComponent = tuple.getSourceComponent();
        String sourceStreamId = tuple.getSourceStreamId();
        return sourceComponent.equals(Constants.SYSTEM_COMPONENT_ID)
                && sourceStreamId.equals(Constants.SYSTEM_TICK_STREAM_ID);
    }

    @Override
    public Map<String, Object> getComponentConfiguration() {
        Config conf = new Config();
        conf.put(Config.TOPOLOGY_TICK_TUPLE_FREQ_SECS, 1);
        return conf;
    }

}
