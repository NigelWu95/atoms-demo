package dev.atoms.demo.instances;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.atoms.demo.workspace.*;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;

final class RuntimeProcess {
    final Process process;
    final Path directory;
    final RuntimeEnvironment.Slot slot;
    final UUID id;
    final long created=System.nanoTime();
    volatile long stopping;
    volatile String stopError;
    // One owner per installation. Guarded by this process monitor; never exposed to the browser.
    String sessionCookie;
    private RuntimeProcess(Process process,Path directory,RuntimeEnvironment.Slot slot,UUID id){this.process=process;this.directory=directory;this.slot=slot;this.id=id;}
    static RuntimeProcess start(RuntimeEnvironment.Settings settings,RuntimeEnvironment.Slot slot,UUID id,byte[] jar) throws IOException {
        // A preflight conflict is never a reason to kill another listener.
        checkPort(slot.port());
        byte[] config=BuildArtifacts.read(slot.config(),16*1024);
        if(!slot.fingerprint().equals(BuildArtifacts.hash(config)))throw new IOException("Runtime configuration changed");
        var properties=new Properties();properties.load(new ByteArrayInputStream(config));
        var runtime=RuntimeConfiguration.parse(properties);
        if(!runtime.id().equals(slot.id()) || runtime.port()!=slot.port())throw new IOException("Runtime installation changed");
        Path directory=settings.paths().create(id);
        write(directory.resolve("app.jar"),jar);
        var buffer=new ByteArrayOutputStream();runtime.forExecution(id).store(buffer,"Managed runtime; private");
        write(directory.resolve("application.properties"),buffer.toByteArray());
        for(String resource:List.of("runtime-supervisor.mjs","supervisor.mjs","protocol.mjs")) {
            try(var input=RuntimeProcess.class.getResourceAsStream("/workspace/"+resource)){
                if(input==null)throw new IOException("Runtime supervisor unavailable");
                byte[] bytes=input.readNBytes(128*1024+1);if(bytes.length>128*1024)throw new IOException("Runtime supervisor unavailable");
                write(directory.resolve(resource),bytes);
            }
        }
        var tools=settings.tools();
        var builder=new ProcessBuilder(tools.node().toString(),directory.resolve("runtime-supervisor.mjs").toString(),
                tools.java().toString(),directory.resolve("app.jar").toString(),directory.resolve("application.properties").toString(),directory.toString(),id.toString());
        builder.directory(directory.toFile()).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD);
        builder.environment().clear();builder.environment().putAll(tools.environment());builder.environment().put("TMPDIR",directory.toString());
        return new RuntimeProcess(builder.start(),directory,slot,id);
    }
    synchronized void stop(String error) {
        if(stopping!=0)return;stopError=error;stopping=System.nanoTime();
        try{process.getOutputStream().write("STOP\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII));process.getOutputStream().flush();}
        catch(IOException ignored){try{process.getOutputStream().close();}catch(IOException ignoredAgain){}}
    }
    static void checkPort(int port) throws IOException {
        // Match Tomcat's restart semantics: TIME_WAIT is not an active listener.
        // SO_REUSEPORT stays disabled, so another listening process still causes rejection.
        try(var socket=new ServerSocket()){socket.setReuseAddress(true);socket.bind(new InetSocketAddress("127.0.0.1",port));}
    }
    static boolean settled(Path directory,UUID id) {
        try{
            var node=new ObjectMapper().readTree(BuildArtifacts.read(directory.resolve("exit.json"),1024));
            return node.isObject() && node.size()==2 && id.toString().equals(node.path("id").asText())
                    && node.path("settled").isBoolean() && node.path("settled").booleanValue();
        }catch(Exception error){return false;}
    }
    private static void write(Path path,byte[] bytes)throws IOException {
        try(var output=Files.newByteChannel(path,Set.of(StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))){
            var buffer=java.nio.ByteBuffer.wrap(bytes);while(buffer.hasRemaining())output.write(buffer);
        }
    }
}
