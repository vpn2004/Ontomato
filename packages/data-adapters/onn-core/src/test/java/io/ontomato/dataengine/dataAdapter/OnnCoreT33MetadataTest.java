package io.ontomato.dataengine.dataAdapter;

import com.alibaba.fastjson2.JSONObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OnnCoreT33MetadataTest {
    @Test void concurrentPostReadValidationsDoNotHoldTheEntryLockAcrossHttp() throws Exception {
        try (Fixture f = new Fixture()) {
            var schema = OnnCoreMetadataCache.schema(f.client, new ArrayList<>());
            f.block = true;
            ExecutorService workers = Executors.newFixedThreadPool(2);
            try {
                Future<?> first = workers.submit(() -> validate(f.client, schema));
                assertTrue(f.entered.await(2, TimeUnit.SECONDS));
                Future<?> second = workers.submit(() -> validate(f.client, schema));
                assertTrue(f.concurrent.await(2, TimeUnit.SECONDS), "两个读取后校验必须各自发起HTTP，不能持entry锁串行等待");
                f.release.countDown();first.get(3, TimeUnit.SECONDS);second.get(3, TimeUnit.SECONDS);
                assertEquals(3, f.requests.get());
            } finally { f.release.countDown(); workers.shutdownNow(); }
        }
    }

    @Test void interruptedWaiterDoesNotWaitForAnotherValidationHttp() throws Exception {
        try (Fixture f = new Fixture()) {
            var schema = OnnCoreMetadataCache.schema(f.client, new ArrayList<>());
            f.block=true;
            ExecutorService workers = Executors.newSingleThreadExecutor();
            try {
                Future<?> first=workers.submit(() -> validate(f.client,schema));
                assertTrue(f.entered.await(2,TimeUnit.SECONDS));
                CountDownLatch finished=new CountDownLatch(1);
                Thread waiter=new Thread(() -> { try { validate(f.client,schema); } catch (RuntimeException expected) { } finally {finished.countDown();} });
                waiter.start();waiter.interrupt();
                assertTrue(finished.await(1,TimeUnit.SECONDS),"取消不能卡在另一个请求的网络锁后");
                f.release.countDown();first.get(3,TimeUnit.SECONDS);waiter.join(1000);
            } finally {f.release.countDown();workers.shutdownNow();}
        }
    }

    @Test void invalidationDuringHttpRejectsOldPublication() throws Exception {
        try(Fixture f=new Fixture()) {
            var schema=OnnCoreMetadataCache.schema(f.client,new ArrayList<>());
            f.block=true;
            ExecutorService workers=Executors.newSingleThreadExecutor();
            try {
                Future<?> old=workers.submit(() -> validate(f.client,schema));
                assertTrue(f.entered.await(2,TimeUnit.SECONDS));
                OnnCoreMetadataCache.invalidate(f.client);
                f.release.countDown();
                assertThrows(ExecutionException.class,() -> old.get(3,TimeUnit.SECONDS));
            } finally {f.release.countDown();workers.shutdownNow();}
        }
    }

    @Test void lateColdResponseCannotOverwriteNewerSchema() throws Exception {
        try (Fixture f = new Fixture()) {
            f.coldReorder = true;
            ExecutorService worker = Executors.newSingleThreadExecutor();
            try {
                Future<?> old = worker.submit(() -> { try { return OnnCoreMetadataCache.schema(f.client, new ArrayList<>()); } catch (Exception e) { throw new RuntimeException(e); } });
                assertTrue(f.entered.await(2, TimeUnit.SECONDS));
                OnnCoreSchema fresh = OnnCoreMetadataCache.schema(f.client, new ArrayList<>());
                f.release.countDown();
                assertThrows(ExecutionException.class, () -> old.get(3, TimeUnit.SECONDS));
                assertEquals(fresh.fingerprint, OnnCoreMetadataCache.schema(f.client, new ArrayList<>()).fingerprint);
            } finally { f.release.countDown(); worker.shutdownNow(); }
        }
    }

    private static void validate(OnnCoreHttpClient client, OnnCoreSchema schema) {
        try {OnnCoreMetadataCache.validate(client,schema,new ArrayList<>());}
        catch(Exception e) {throw new RuntimeException(e);}
    }
    static final class Fixture implements AutoCloseable {
        final HttpServer server;
        final ExecutorService executor=Executors.newCachedThreadPool();
        final AtomicInteger requests=new AtomicInteger(),active=new AtomicInteger();
        final CountDownLatch entered=new CountDownLatch(1),concurrent=new CountDownLatch(1),release=new CountDownLatch(1);
        final OnnCoreHttpClient client;
        volatile boolean block, coldReorder;
        Fixture() throws Exception {
            server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.setExecutor(executor);
            server.createContext("/api/v1/schema/all",this::schema);server.start();
            client=new OnnCoreHttpClient("http://127.0.0.1:"+server.getAddress().getPort());
        }
        void schema(HttpExchange exchange) throws java.io.IOException {
            int sequence = requests.incrementAndGet();int count=active.incrementAndGet();
            JSONObject wire = OnnCoreGraphCompilerTest.schemaWire();
            if (coldReorder) wire.getJSONArray("ontologies").getJSONObject(0).put("description", sequence == 1 ? "old" : "new");
            try {
                if (coldReorder && sequence == 1) { entered.countDown(); try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }
                if(block) {entered.countDown();if(count>=2)concurrent.countDown();try {release.await(5,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}}
                exchange.getResponseHeaders().set("ETag","\"schema-1\"");
                if(exchange.getRequestHeaders().getFirst("If-None-Match")!=null) {exchange.sendResponseHeaders(304,-1);return;}
                byte[] body=com.alibaba.fastjson2.JSON.toJSONBytes(wire);exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);
            } finally {active.decrementAndGet();exchange.close();}
        }
        public void close() {release.countDown();server.stop(0);executor.shutdownNow();OnnCoreMetadataCache.invalidate(client);}
    }
}
