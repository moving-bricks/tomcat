/*
 */
package org.apache.tomcat.lite.http;

import java.io.BufferedReader;
import java.io.IOException;

import junit.framework.TestCase;

import org.apache.tomcat.lite.http.HttpChannel.HttpService;
import org.apache.tomcat.lite.http.HttpChannel.RequestCompleted;
import org.apache.tomcat.lite.io.BBuffer;
import org.apache.tomcat.lite.io.CBuffer;
import org.apache.tomcat.lite.io.IOBuffer;
import org.apache.tomcat.lite.io.IOChannel;
import org.apache.tomcat.lite.io.IOConnector;
import org.apache.tomcat.lite.io.MemoryIOConnector;
import org.apache.tomcat.lite.io.MemoryIOConnector.MemoryIOChannel;

public class HttpChannelInMemoryTest extends TestCase {
    
    MemoryIOConnector memoryServerConnector =  new MemoryIOConnector();
    MemoryIOConnector memoryClientConnector = 
        new MemoryIOConnector().withServer(memoryServerConnector);

    
    // Used for pipelined requests - after the first request is 
    // processed, a new HttpChannel is used ( first may still be 
    // in use )
    HttpChannel lastServer;
    
    // The server channel will use this for I/O...
    MemoryIOConnector.MemoryIOChannel net = new MemoryIOChannel();

    HttpConnector serverConnector = new HttpConnector(memoryServerConnector) {
        @Override
        public HttpChannel get(CharSequence target) throws IOException {
            throw new IOException();
        }
        public HttpChannel getServer() {
            lastServer = new HttpChannel().serverMode(true);
            lastServer.withBuffers(net);
            lastServer.setConnector(this);
            //lastServer.withIOConnector(memoryServerConnector);
            return lastServer;
        }
    };

    HttpConnector httpClient = new HttpConnector(memoryClientConnector);

    boolean hasBody = false;
    boolean bodyDone = false;
    boolean bodySentDone = false;
    boolean headersDone = false;
    boolean allDone = false;
    
    
    HttpChannel http = serverConnector.getServer();
    
    public void setUp() throws IOException {
        serverConnector.setHttpService(null);
    }
    
   
    public void test2Req() throws IOException {
        String req = "GET /index.html?q=b&c=d HTTP/1.1\r\n" +
        "Host:  Foo.com \n" + 
        "H2:Bar\r\n" + 
        "H3: Foo \r\n" +
        " Bar\r\n" +
        "H4: Foo\n" +
        "    Bar\n" +
        "\r\n" + 
        "HEAD /r2? HTTP/1.1\n" +
        "Host: Foo.com\r\n" +
        "H3: Foo \r\n" +
        "       Bar\r\n" +
        "H4: Foo\n" +
        " Bar\n" +
        "\r\n";
        net.getIn().append(req);        
        
        assertTrue(http.getRequest().method().equals("GET"));
        assertTrue(http.getRequest().protocol().equals("HTTP/1.1"));
        assertEquals(http.getRequest().getMimeHeaders().size(), 4);
        assertEquals(http.getRequest().getMimeHeaders().getHeader("Host").toString(),
                "Foo.com");
        assertEquals(http.getRequest().getMimeHeaders().getHeader("H2").toString(),
                "Bar");
        
        http.getOut().append("Response1");
        http.getOut().close();
        http.startSending();
        http.release(); 
        
        // now second response must be in. 
        // the connector will create a new http channel
        
        http = lastServer;
        
        assertTrue(http.getRequest().method().equals("HEAD"));
        assertTrue(http.getRequest().protocol().equals("HTTP/1.1"));
        assertTrue(http.getRequest().getMimeHeaders().size() == 3);
        assertTrue(http.getRequest().getMimeHeaders().getHeader("Host")
                .equals("Foo.com"));
    }

    public void testMultiLineHead() throws IOException {
        http.getNet().getIn().append("GET / HTTP/1.0\n" +
                "Cookie: 1234\n" +
                "  456 \n" +
                "Connection:   Close\n\n");
        http.getNet().getIn().close();
        
        MultiMap headers = http.getRequest().getMimeHeaders();
        CBuffer cookie = headers.getHeader("Cookie");
        CBuffer conn = headers.getHeader("Connection");
        assertEquals(conn.toString(), "Close");
        assertEquals(cookie.toString(), "1234 456");
        
        assertEquals(http.headRecvBuf.toString(), 
                "GET / HTTP/1.0\n" +
                "Cookie: 1234 456   \n" + // \n -> trailing space
                "Connection:   Close\n\n");
    }

    public void testCloseSocket() throws IOException {
        http.getNet().getIn().append("GET / HTTP/1.1\n"
                + "Host: localhost\n"
                + "\n");
        assertTrue(http.keepAlive());

        http.getNet().getIn().close();
        assertFalse(http.keepAlive());
    }
    
    public void test2ReqByte2Byte() throws IOException {
        String req = "GET /index.html?q=b&c=d HTTP/1.1\r\n" +
        "Host:  Foo.com \n" + 
        "H2:Bar\r\n" + 
        "H3: Foo \r\n" +
        " Bar\r\n" +
        "H4: Foo\n" +
        "    Bar\n" +
        "\r\n" + 
        "HEAD /r2? HTTP/1.1\n" +
        "Host: Foo1.com\n" +
        "H3: Foo \r\n" +
        "       Bar\r\n" +
        "\r\n";
        for (int i = 0; i < req.length(); i++) {
            net.getIn().append(req.charAt(i));        
        }
        
        assertTrue(http.getRequest().method().equals("GET"));
        assertTrue(http.getRequest().protocol().equals("HTTP/1.1"));
        assertTrue(http.getRequest().getMimeHeaders().size() == 4);
        assertTrue(http.getRequest().getMimeHeaders().getHeader("Host")
                .equals("Foo.com"));
        
        // send a response
        http.sendBody.append("Response1");
        http.getOut().close();
        
        http.startSending(); // This will trigger a pipelined request
        
        http.release(); // now second response must be in
        
        http = lastServer;
        assertTrue(http.getRequest().method().equals("HEAD"));
        assertTrue(http.getRequest().protocol().equals("HTTP/1.1"));
        assertTrue(http.getRequest().getMimeHeaders().size() == 2);
        assertTrue(http.getRequest().getMimeHeaders().getHeader("Host")
                .equals("Foo1.com"));

        // send a response - service method will be called
        http.sendBody.append("Response2");
        http.getOut().close();
        http.release(); // now second response must be in
        
        
    }
    
    public void testEndWithoutFlushCallbacks() throws IOException {
        http.setCompletedCallback(new RequestCompleted() {
            public void handle(HttpChannel data, Object extra)
            throws IOException {
                allDone = true;
            }
        });
        http.getNet().getIn().append(POST);
        http.getNet().getIn().close();
        
        http.sendBody.queue("Hi");
        http.getOut().close();
        http.startSending(); // will call handleEndSend

        assertTrue(allDone);
        
    }

    public void testCallbacks() throws IOException {
        http.setCompletedCallback(new RequestCompleted() {
            public void handle(HttpChannel data, Object extra)
            throws IOException {
                allDone = true;
            }
        });
        http.setHttpService(new HttpService() {
            public void service(HttpRequest httpReq, HttpResponse httpRes)
            throws IOException {
                headersDone = true;
            }
        });
        http.setDataReceivedCallback(new IOConnector.DataReceivedCallback() {
            @Override
            public void handleReceived(IOChannel ch) throws IOException {
                if (ch.getIn().isAppendClosed()) {
                    bodyDone = true;
                }
            }
        });
        http.setDataFlushedCallback(new IOConnector.DataFlushedCallback() {
            @Override
            public void handleFlushed(IOChannel ch) throws IOException {
                if (ch.getOut().isAppendClosed()) {
                    bodySentDone = true;
                }
            }
        });

        // Inject the request
        http.getNet().getIn().append(POST);
        assertTrue(headersDone);
        http.getNet().getIn().append("1234");
        
        http.getNet().getIn().close();
        assertTrue(bodyDone);
        
        
        http.sendBody.queue("Hi");
        http.getOut().close();
        http.startSending();
        assertTrue(bodySentDone);

        assertTrue(allDone);
        
    }
    
    public static String POST = "POST / HTTP/1.0\n" +
        "Connection: Close\n" +
        "Content-Length: 4\n\n" +
        "1234"; 

    public void testClose() throws IOException {
        http.getNet().getIn().append(POST);
        http.getNet().getIn().close();
        
        HttpBody receiveBody = http.receiveBody;
        IOBuffer appData = receiveBody;
        BBuffer res = BBuffer.allocate(1000);
        appData.readAll(res);
        
        assertEquals(res.toString(), "1234");
        assertFalse(http.keepAlive());
        assertFalse(http.keepAlive());
        
        http.sendBody.queue(res);
        http.getOut().close();
        http.startSending();
        
        assertTrue(net.getOut().isAppendClosed());
        assertTrue(net.out.toString().indexOf("\n1234") > 0);
        
    }
    
    public void testReadLine() throws Exception {
        http.getNet().getIn().append("POST / HTTP/1.0\n" +
        		"Content-Length: 28\n\n" +
                "Line 1\n" +
                "Line 2\r\n" +
                "Line 3\r" +
                "Line 4");
        http.getNet().getIn().close();
        
        BufferedReader r = http.getRequest().getReader();
        assertEquals("Line 1", r.readLine());
        assertEquals("Line 2", r.readLine());
        assertEquals("Line 3", r.readLine());
        assertEquals("Line 4", r.readLine());
        assertEquals(null, r.readLine());
        
    }
}
