package dev.atoms.demo.projectdelivery;

/** Install before the JDK HTTP implementation is initialized. Platform Tomcat is unaffected. */
public final class PublicationServerLimits {
    private PublicationServerLimits() {}
    public static void install() {
        System.setProperty("jdk.httpserver.maxConnections", "64");
        System.setProperty("sun.net.httpserver.maxIdleConnections", "8");
        System.setProperty("sun.net.httpserver.idleInterval", "10");
        System.setProperty("sun.net.httpserver.maxReqTime", "15");
        System.setProperty("sun.net.httpserver.maxRspTime", "45");
        System.setProperty("sun.net.httpserver.maxReqHeaders", "64");
        System.setProperty("sun.net.httpserver.maxReqHeaderSize", "32768");
    }
}
