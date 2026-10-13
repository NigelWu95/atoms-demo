package dev.atoms.demo.instances;

import dev.atoms.demo.common.ApiException;
import java.net.*;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** No browser-chosen origin, redirect, environment proxy or unbounded upstream body. */
final class RuntimeHttp {
    private static final Set<String> PATHS=Set.of("/api/health","/api/csrf","/api/session","/api/app","/api/app/document","/api/state");
    private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
            .followRedirects(HttpClient.Redirect.NEVER).proxy(new ProxySelector(){
                public List<Proxy> select(URI uri){return List.of(Proxy.NO_PROXY);}
                public void connectFailed(URI uri,SocketAddress address,java.io.IOException failure){}
            }).build();
    record Reply(int status,byte[] bytes,String cookie,String type) {
        @Override public String toString(){return "RuntimeReply[status="+status+"]";}
    }
    Reply send(int port,UUID execution,UUID installation,String path,String method,byte[] body,Map<String,String> headers) {
        if(port<1024 || port>65535 || !PATHS.contains(path) || !Set.of("GET","POST","PUT").contains(method)
                || !Set.of("Cookie","X-CSRF-TOKEN","Idempotency-Key").containsAll(headers.keySet())
                || body!=null && body.length>320*1024)throw new IllegalArgumentException("Invalid runtime request");
        var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(5))
                .header("Accept",path.endsWith("/document")?"text/html":"application/json");
        headers.forEach(builder::header);
        if(body!=null)builder.header("Content-Type","application/json");
        builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofByteArray(body));
        var future=client.sendAsync(builder.build(),ignored->new Bounded());
        try {
            var response=future.get(5,TimeUnit.SECONDS);
            if(response.statusCode()>=300 && response.statusCode()<400
                    || !execution.toString().equals(response.headers().firstValue("X-Atoms-Execution-Id").orElse("")))throw unavailable();
            String cookie=null;
            for(String value:response.headers().allValues("Set-Cookie")) {
                String pair=value.split(";",2)[0];
                if(!pair.matches("STANDALONE_"+installation+"=[A-Za-z0-9_+/=-]{1,256}"))throw unavailable();
                cookie=pair;
            }
            return new Reply(response.statusCode(),response.body(),cookie,response.headers().firstValue("Content-Type").orElse(""));
        } catch(InterruptedException error){Thread.currentThread().interrupt();throw unavailable();}
        catch(ExecutionException|TimeoutException error){throw unavailable();}
        finally{if(!future.isDone())future.cancel(true);}
    }
    static ApiException unavailable(){return new ApiException(503,"RUNTIME_UNAVAILABLE","独立实例暂不可用或运行身份不匹配；未自动重试操作");}
    private static final class Bounded implements HttpResponse.BodySubscriber<byte[]> {
        private final HttpResponse.BodySubscriber<byte[]> delegate=HttpResponse.BodySubscribers.ofByteArray();
        private Flow.Subscription subscription;private int size;private boolean stopped;
        public CompletionStage<byte[]> getBody(){return delegate.getBody();}
        public void onSubscribe(Flow.Subscription value){subscription=value;delegate.onSubscribe(value);}
        public void onNext(List<ByteBuffer> chunks){
            if(stopped)return;
            for(var chunk:chunks){
                if(chunk.remaining()>1024*1024-size){stopped=true;subscription.cancel();delegate.onError(unavailable());return;}
                size+=chunk.remaining();
            }
            delegate.onNext(chunks);
        }
        public void onError(Throwable error){if(!stopped){stopped=true;delegate.onError(error);}}
        public void onComplete(){if(!stopped){stopped=true;delegate.onComplete();}}
    }
}
