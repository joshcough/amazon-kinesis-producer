// Copyright 2015 Amazon.com, Inc. or its affiliates. All Rights Reserved.
//
// Licensed under the Amazon Software License (the "License").
// You may not use this file except in compliance with the License.
// A copy of the License is located at
//
//  http://aws.amazon.com/asl
//
// or in the "license" file accompanying this file. This file is distributed
// on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
// express or implied. See the License for the specific language governing
// permissions and limitations under the License.

package com.amazonaws.services.kinesis.producer;

import static com.confluex.mock.http.matchers.HttpMatchers.anyRequest;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.commons.lang.StringUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.internal.StaticCredentialsProvider;
import com.confluex.mock.http.ClientRequest;
import com.confluex.mock.http.MockHttpsServer;
import com.google.common.collect.ImmutableList;

public class KinesisProducerTest {
    @SuppressWarnings("unused")
    private static final Logger log = LoggerFactory.getLogger(KinesisProducerTest.class);
    
    private MockHttpsServer server;
    private int port;
    
    private static class RotatingAwsCredentialsProvider implements AWSCredentialsProvider {
        private final List<AWSCredentials> creds;
        private final AtomicInteger idx = new AtomicInteger(0);
        
        public RotatingAwsCredentialsProvider(List<AWSCredentials> creds) {
            this.creds = creds;
        }
        
        @Override
        public AWSCredentials getCredentials() {
            return creds.get(idx.getAndIncrement() % creds.size());
        }

        @Override
        public void refresh() { }
    }
    
    @Before
    public void startServer() {
        server = new MockHttpsServer();
        port = server.getPort();
        server.respondTo(anyRequest()).withStatus(403).withBody("(test)");
    }
    
    @After
    public void stopServer() {
        server.stop();
    }
    
    @Test
    public void differentCredsForRecordsAndMetrics() throws InterruptedException, ExecutionException {
        final String AKID_A = "AKIAAAAAAAAAAAAAAAAA";
        final String AKID_B = "AKIABBBBBBBBBBBBBBBB";
        
        final KinesisProducer kp = getProducer(
                new StaticCredentialsProvider(
                        new BasicAWSCredentials(AKID_A, StringUtils.repeat("a", 40))),
                new StaticCredentialsProvider(
                        new BasicAWSCredentials(AKID_B, StringUtils.repeat("b", 40))));
        
        final long start = System.nanoTime();
        while (System.nanoTime() - start < 500 * 1000000) {
            kp.addUserRecord("a", "a", ByteBuffer.wrap(new byte[0]));
            kp.flush();
            Thread.sleep(10);
        }
        
        kp.flushSync();
        kp.destroy();
        
        Map<String, AtomicInteger> counts = new HashMap<String, AtomicInteger>();
        counts.put(AKID_A, new AtomicInteger(0));
        counts.put(AKID_B, new AtomicInteger(0));
        
        for (ClientRequest cr : server.getRequests()) {
            String auth = cr.getHeaders().get("Authorization");
            String host = cr.getHeaders().get("Host");
            if (auth.contains(AKID_B)) {
                assertFalse(host.contains("kinesis"));
                counts.get(AKID_B).getAndIncrement();
            } else if (auth.contains(AKID_A)) {
                assertFalse(host.contains("monitoring"));
                counts.get(AKID_A).getAndIncrement();
            } else {
                fail("Expected AKID(s) not found in auth header");
            }
        }
        
        assertTrue(counts.get(AKID_A).get() > 1);
        assertTrue(counts.get(AKID_B).get() > 1);
    }
    
    @Test
    public void rotatingCredentials() throws InterruptedException, ExecutionException {
        final String AKID_C = "AKIACCCCCCCCCCCCCCCC";
        final String AKID_D = "AKIDDDDDDDDDDDDDDDDD";
        
        final KinesisProducer kp = getProducer(
                new RotatingAwsCredentialsProvider(ImmutableList.<AWSCredentials>of(
                        new BasicAWSCredentials(AKID_C, StringUtils.repeat("c", 40)),
                        new BasicAWSCredentials(AKID_D, StringUtils.repeat("d", 40)))),
                null);
        
        final long start = System.nanoTime();
        while (System.nanoTime() - start < 500 * 1000000) {
            kp.addUserRecord("a", "a", ByteBuffer.wrap(new byte[0]));
            kp.flush();
            Thread.sleep(10);
        }
        
        kp.flushSync();
        kp.destroy();
        
        Map<String, AtomicInteger> counts = new HashMap<String, AtomicInteger>();
        counts.put(AKID_C, new AtomicInteger(0));
        counts.put(AKID_D, new AtomicInteger(0));
        
        for (ClientRequest cr : server.getRequests()) {
            String auth = cr.getHeaders().get("Authorization");
            if (auth.contains(AKID_C)) {
                counts.get(AKID_C).getAndIncrement();
            } else if (auth.contains(AKID_D)) {
                counts.get(AKID_D).getAndIncrement();
            } else {
                fail("Expected AKID(s) not found in auth header");
            }
        }
        
        assertTrue(counts.get(AKID_C).get() > 1);
        assertTrue(counts.get(AKID_D).get() > 1);
    }
    
    private KinesisProducer getProducer(AWSCredentialsProvider provider, AWSCredentialsProvider metrics_creds_provider) {
        final KinesisProducerConfiguration cfg = new KinesisProducerConfiguration()
                .setCustomEndpoint("localhost")
                .setPort(port)
                .setVerifyCertificate(false)
                .setAggregationEnabled(false)
                .setCredentialsProvider(provider)
                .setCredentialsRefreshDelay(100)
                .setRegion("us-west-1")
                .setRecordTtl(200)
                .setMetricsUploadDelay(100);
        if (metrics_creds_provider != null) {
            cfg.setMetricsCredentialsProvider(metrics_creds_provider);
        }
        return new KinesisProducer(cfg);
    }
}
