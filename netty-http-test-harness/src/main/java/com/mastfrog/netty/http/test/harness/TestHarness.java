package com.mastfrog.netty.http.test.harness;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.Sets;
import com.google.common.net.MediaType;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.mastfrog.acteur.util.ErrorInterceptor;
import com.mastfrog.acteur.headers.HeaderValueType;
import com.mastfrog.acteur.headers.Headers;
import static com.mastfrog.acteur.headers.Headers.SET_COOKIE_B;
import com.mastfrog.acteur.headers.Method;
import com.mastfrog.acteur.util.Server;
import com.mastfrog.acteur.util.ServerControl;
import com.mastfrog.giulius.ShutdownHookRegistry;
import com.mastfrog.netty.http.client.CookieStore;
import com.mastfrog.netty.http.client.HttpClient;
import com.mastfrog.netty.http.client.HttpRequestBuilder;
import com.mastfrog.netty.http.client.ResponseFuture;
import com.mastfrog.netty.http.client.ResponseHandler;
import com.mastfrog.netty.http.client.State;
import com.mastfrog.netty.http.client.StateType;
import static com.mastfrog.netty.http.client.StateType.Cancelled;
import static com.mastfrog.netty.http.client.StateType.Closed;
import static com.mastfrog.netty.http.client.StateType.ContentReceived;
import static com.mastfrog.netty.http.client.StateType.Error;
import static com.mastfrog.netty.http.client.StateType.Finished;
import static com.mastfrog.netty.http.client.StateType.FullContentReceived;
import static com.mastfrog.netty.http.client.StateType.HeadersReceived;
import com.mastfrog.settings.Settings;
import com.mastfrog.url.Protocol;
import com.mastfrog.url.URL;
import com.mastfrog.util.thread.Receiver;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufInputStream;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.lang.reflect.Array;
import java.net.DatagramSocket;
import java.net.ServerSocket;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.joda.time.Duration;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import com.mastfrog.util.Exceptions;
import io.netty.handler.codec.http.Cookie;
import java.util.ArrayList;
import java.util.Date;
import java.util.EnumMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.fail;

/**
 * A general purpose test harness for Web applications. Note: Your module should
 * <pre>
 * bind(ErrorHandler.class).to(TestHarness.class);
 * </pre> to ensure that server-side exceptions are thrown when you call
 * <code>CallHandler.throwIfError()</code>
 *
 * @author Tim Boudreau
 */
@Singleton
public class TestHarness implements ErrorInterceptor {

    private final Server server;
    private final HttpClient client;
    private final int port;
    private final ObjectMapper mapper;

    public TestHarness(Server server, Settings settings, ShutdownHookRegistry reg, HttpClient client) throws IOException {
        this(server, settings, reg, client, new ObjectMapper());
    }

    @Inject
    public TestHarness(Server server, Settings settings, ShutdownHookRegistry reg, HttpClient client, ObjectMapper mapper) throws IOException {
        this.server = server;
        port = settings.getInt("testPort", findPort());
        this.client = client;
        if (reg != null) {
            reg.add(new Shutdown());
        }
        this.mapper = mapper;
    }

    public HttpClient client() {
        return client;
    }

    /**
     * Constructor for manual construction
     *
     * @param port The port
     * @param client An http client
     */
    public TestHarness(int port, HttpClient client, ObjectMapper mapper) {
        this.server = new Fake(port);
        this.port = port;
        this.client = client;
        this.mapper = mapper == null ? new ObjectMapper() : mapper;
    }

    public Server getServer() {
        return server;
    }

    private int findPort() {
        Random r = new Random(System.currentTimeMillis());
        int port;
        do {
            port = r.nextInt(4000) + 4000;
        } while (!available(port));
        return port;
    }

    private boolean available(int port) {
        ServerSocket ss = null;
        DatagramSocket ds = null;
        try {
            ss = new ServerSocket(port);
            ss.setReuseAddress(true);
            ds = new DatagramSocket(port);
            ds.setReuseAddress(true);
            return true;
        } catch (IOException e) {
        } finally {
            if (ds != null) {
                ds.close();
            }
            if (ss != null) {
                try {
                    ss.close();
                } catch (IOException e) {
                    /* should not be thrown */
                }
            }
        }
        return false;
    }

    public int getPort() {
        return port;
    }

    private static Throwable err;

    @Override
    public void onError(Throwable err) {
        this.err = err;
    }

    public static void throwIfError() throws Throwable {
        Throwable old = err;
        err = null;
        if (old != null) {
            throw old;
        }
    }

    private class Shutdown implements Runnable {

        @Override
        public void run() {
            client.shutdown();
            try {
                if (serverStart != null) {
                    serverStart.shutdown(true);
                }
            } catch (Exception ex) {
                Exceptions.printStackTrace(ex);
            }
        }
    }

    public TestRequestBuilder get(String... pathElements) {
        return request(Method.GET, pathElements);
    }

    public TestRequestBuilder put(String... pathElements) {
        return request(Method.PUT, pathElements);
    }

    public TestRequestBuilder post(String... pathElements) {
        return request(Method.POST, pathElements);
    }

    public TestRequestBuilder delete(String... pathElements) {
        return request(Method.DELETE, pathElements);
    }

    public TestRequestBuilder options(String... pathElements) {
        return request(Method.OPTIONS, pathElements);
    }

    public TestRequestBuilder head(String... pathElements) {
        return request(Method.HEAD, pathElements);
    }

    public TestRequestBuilder trace(String... pathElements) {
        return request(Method.TRACE, pathElements);
    }

    private volatile ServerControl serverStart;

    public TestRequestBuilder request(Method m, String... pathElements) {
        if (serverStart == null) {
            synchronized (this) {
                if (serverStart == null) {
                    try {
                        serverStart = server.start(port);
                    } catch (IOException ex) {
                        Exceptions.chuck(ex);
                    }
                }
            }
        }
        TestRequestBuilder result = new TestRequestBuilder(client.request(m).setHost("localhost").setPort(server.getPort()), mapper);
        for (String el : pathElements) {
            String[] parts = el.split("/");
            for (String part : parts) {
                if (part.isEmpty()) {
                    continue;
                }
                result.addPathElement(part);
            }
        }
        return result;
    }

    public static class TestRequestBuilder implements HttpRequestBuilder {

        private final HttpRequestBuilder bldr;
        private Duration timeout = Duration.standardSeconds(10);
        private final ObjectMapper mapper;

        TestRequestBuilder(HttpRequestBuilder bldr, ObjectMapper mapper) {
            this.bldr = bldr;
            this.mapper = mapper;
        }

        @Override
        public HttpRequestBuilder dontAggregateResponse() {
            bldr.dontAggregateResponse();
            return this;
        }

        public TestRequestBuilder setTimeout(Duration dur) {
            assertNotNull(dur);
            this.timeout = dur;
            return this;
        }

        public TestRequestBuilder setCookieStore(CookieStore store) {
            bldr.setCookieStore(store);
            return this;
        }

        @Override
        public <T> TestRequestBuilder addHeader(HeaderValueType<T> type, T value) {
            bldr.addHeader(type, value);
            return this;
        }

        @Override
        public TestRequestBuilder addPathElement(String element) {
            bldr.addPathElement(element);
            return this;
        }

        @Override
        public TestRequestBuilder addQueryPair(String key, String value) {
            bldr.addQueryPair(key, value);
            return this;
        }

        @Override
        public TestRequestBuilder setAnchor(String anchor) {
            bldr.setAnchor(anchor);
            return this;
        }

        @Override
        public TestRequestBuilder setHost(String host) {
            bldr.setHost(host);
            return this;
        }

        @Override
        public TestRequestBuilder setPath(String path) {
            bldr.setPath(path);
            return this;
        }

        @Override
        public TestRequestBuilder setPort(int port) {
            bldr.setPort(port);
            return this;
        }

        @Override
        public TestRequestBuilder setProtocol(Protocol protocol) {
            bldr.setProtocol(protocol);
            return this;
        }

        @Override
        public TestRequestBuilder setURL(URL url) {
            bldr.setURL(url);
            return this;
        }

        @Override
        public TestRequestBuilder setURL(String url) {
            bldr.setURL(url);
            return this;
        }

        @Override
        public TestRequestBuilder setUserName(String userName) {
            bldr.setUserName(userName);
            return this;
        }

        @Override
        public TestRequestBuilder setPassword(String password) {
            bldr.setPassword(password);
            return this;
        }

        @Override
        public TestRequestBuilder basicAuthentication(String username, String password) {
            bldr.basicAuthentication(username, password);
            return this;
        }

        private ResponseFuture future;

        @Override
        public ResponseFuture execute(ResponseHandler<?> response) {
            return future = bldr.execute(response);
        }

        @Override
        public ResponseFuture execute() {
            return future = bldr.execute();
        }

        @Override
        public TestRequestBuilder setBody(Object o, MediaType contentType) throws IOException {
            if (o != null && !(o instanceof CharSequence)) {
                // The HttpClient does not have our object mapper
                o = mapper.writeValueAsString(o);
            }
            bldr.setBody(o, contentType);
            return this;
        }

        @Override
        public <T> TestRequestBuilder on(Class<? extends State<T>> event, Receiver<T> r) {
            bldr.on(event, r);
            return this;
        }

        @Override
        public <T> TestRequestBuilder on(StateType event, Receiver<T> r) {
            bldr.on(event, r);
            return this;
        }

        @Override
        public TestRequestBuilder onEvent(Receiver<State<?>> r) {
            bldr.onEvent(r);
            return this;
        }

        @Override
        public URL toURL() {
            return bldr.toURL();
        }

        private boolean log;

        public TestRequestBuilder log() {
            this.log = true;
            return this;
        }

        public CallResult go() {
            CallResultImpl impl = new CallResultImpl(toURL(), timeout, log, mapper);
            onEvent(impl);
            impl.future = execute();
            return impl;
        }

        @Override
        public HttpRequestBuilder noHostHeader() {
            bldr.noHostHeader();
            return this;
        }

        @Override
        public HttpRequestBuilder noConnectionHeader() {
            bldr.noConnectionHeader();
            return this;
        }

        @Override
        public HttpRequestBuilder noDateHeader() {
            bldr.noDateHeader();
            return this;
        }
    }

    private static final class CallResultImpl extends Receiver<State<?>> implements CallResult, Runnable {

        private final URL url;
        private final Set<StateType> states = Sets.newCopyOnWriteArraySet();
        private final AtomicReference<HttpResponseStatus> status = new AtomicReference<>();
        private final AtomicReference<HttpHeaders> headers = new AtomicReference<>();
        private final AtomicReference<ByteBuf> content = new AtomicReference<>();
        private volatile ResponseFuture future;
        private Throwable err;
        private final Map<StateType, NamedLatch> latches = Collections.synchronizedMap(new EnumMap<StateType, NamedLatch>(StateType.class));
        private final Duration timeout;
        private final ObjectMapper mapper;

        private CallResultImpl(URL toURL, Duration timeout, boolean log, ObjectMapper mapper) {
            this.log = log;
            this.url = toURL;
            for (StateType type : StateType.values()) {
                latches.put(type, new NamedLatch(type.name()));
            }
            // Ensure latches are triggered if an event that logically comes after their event is triggered,
            // or on cancel or failure
            setupDependencies();

            this.timeout = timeout;
            this.mapper = mapper;
        }

        private void setupDependencies() {
            depend(Closed, HeadersReceived, ContentReceived, FullContentReceived, Error);
            depend(ContentReceived, HeadersReceived);
            depend(FullContentReceived, HeadersReceived, ContentReceived, Error);
            depend(Finished, HeadersReceived, ContentReceived, FullContentReceived, Closed, Error);
            depend(Cancelled, StateType.values());
            depend(Error, StateType.values());
            depend(FullContentReceived, HeadersReceived, ContentReceived);
        }

        private void depend(StateType trigger, StateType... types) {
            NamedLatch triggerLatch = latches.get(trigger);
            triggerLatch.others = latchesFor(trigger, types);
        }

        private NamedLatch[] latchesFor(StateType exclude, StateType... types) {
            List<NamedLatch> result = new ArrayList<>();
            for (StateType type : types) {
                if (type != exclude) {
                    result.add(latches.get(type));
                }
            }
            return result.toArray(new NamedLatch[result.size()]);
        }

        private String headersToString(HttpHeaders hdrs) {
            if (headers == null) {
                return "[null]";
            }
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, String> e : hdrs.entries()) {
                sb.append(e.getKey()).append(':').append(' ').append(e.getValue()).append('\n');
            }
            return sb.toString();
        }

        public void cancel() {
            assertNotNull("Http call never made", future);
            future.cancel();
        }

        public StateType state() {
            return future == null ? null : future.lastState();
        }

        private final Thread mainThread = Thread.currentThread();
        private AtomicBoolean timedOut = new AtomicBoolean();
        private CountDownLatch timeoutWait = new CountDownLatch(1);

        public void run() {
            try {
                Thread.sleep(timeout.getMillis());
                timedOut.set(true);
                timeoutWait.countDown();
                if (!states.contains(StateType.Closed)) {
                    if (log) {
                        System.out.println("Cancelling request for timeout "
                                + timeout + " " + url.getPathAndQuery());
                    }
                    if (future != null) {
                        future.cancel();
                    }
                    mainThread.interrupt();
                }
            } catch (InterruptedException ex) {
                Exceptions.printStackTrace(ex);
            }
        }

        private boolean log;

        public void log() {
            log = true;
        }

        @Override
        public void receive(State<?> state) {
            if (log) {
                System.out.println(url.getPathAndQuery() + " - " + state.name() + " - " + state.get());
            }
            states.add(state.stateType());
            boolean updateState = true;
            switch (state.stateType()) {
                case Connected:
                    if (timeout.getMillis() != Long.MAX_VALUE) {
                    Thread t = new Thread(this);
                    t.setDaemon(true);
                    t.setName("Timeout thread for " + url.getPathAndQuery());
                    t.setPriority(Thread.NORM_PRIORITY - 1);
                    t.start();
                    }
                    break;
                case SendRequest:
                    State.SendRequest sr = (State.SendRequest) state;
                    if (log) {
                        System.out.println("SENT REQUEST " + headersToString(sr.get().headers()));
                    }
                    break;
                case Timeout:
                    if (log) {
                        System.out.println("TIMEOUT.");
                    }
                case Closed:
                    break;
                case Finished:
                case HeadersReceived:
                    HttpResponse hr = (HttpResponse) state.get();
                    HttpResponseStatus st = hr.getStatus();
                    if (HttpResponseStatus.CONTINUE.equals(st)) {
                        updateState = false;
                    }
                    setStatus(st);
                    setHeaders(hr.headers());
                    break;
                case FullContentReceived:
                    State.FullContentReceived full = (State.FullContentReceived) state;
                    setContent(full.get());
                    break;
                case Error:
                    this.err = (Throwable) state.get();
                    this.err.printStackTrace();
                    break;
            }
            if (updateState) {
                latches.get(state.stateType()).countDown();
            }
        }

        void await(StateType state) throws InterruptedException {
            if (states.contains(state)) {
                return;
            }
            await(latches.get(state));
        }

        void await(ResettableCountDownLatch latch) throws InterruptedException {
            if (log) {
                System.out.println("WAIT ON " + latch);
            }
            latch.await(timeout.getMillis(), TimeUnit.MILLISECONDS);
        }

        @Override
        public CallResult assertStateSeen(StateType type) throws InterruptedException {
            await(latches.get(type));
            assertTrue(type + " not in " + states, states.contains(type));
            return this;
        }

        @Override
        public CallResult assertCode(int code) throws Throwable {
            return assertStatus(HttpResponseStatus.valueOf(code));
        }

        private String contentAsString() throws UnsupportedEncodingException {
            ByteBuf buf = getContent();
            if (buf == null) {
                return null;
            }
            if (!buf.isReadable()) {
                return null;
            }
            buf.resetReaderIndex();
            byte[] b = new byte[buf.readableBytes()];
            buf.readBytes(b);
            buf.resetReaderIndex();
            return new String(b, "UTF-8");
        }

        public String content() throws UnsupportedEncodingException, InterruptedException {
            await(FullContentReceived);
            return contentAsString();
        }

        private ByteBuf getContent() {
            return content.get();
        }

        @Override
        public CallResult assertContentContains(String expected) throws Throwable {
            await(FullContentReceived);
            String s = contentAsString();
            assertNotNull("Content buffer not readable - expected '" + expected + "'", s);
            assertFalse("0 bytes content", s.isEmpty());
            assertTrue("Content does not contain '" + expected + "'", s.contains(expected));
            return this;
        }

        @Override
        public CallResult assertContent(String expected) throws Throwable {
            await(FullContentReceived);
            String s = contentAsString();
            assertNotNull("Content buffer not readable - expected '" + expected + "'", s);
            assertFalse("0 bytes content", s.isEmpty());
            assertEquals(expected, s);
            return this;
        }

        @Override
        public CallResult assertTimedOut() throws Throwable {
            timeoutWait.await(timeout.getMillis() * 2, TimeUnit.MILLISECONDS);
            assertTrue("Did not time out", timedOut.get());
            return this;
        }

        @Override
        public CallResult assertStatus(HttpResponseStatus status) throws Throwable {
            await(HeadersReceived);
            // Handle the case that the status is temporarily 100-CONTINUE - unless
            // we're asserting that that is the status, we want to wait until we get
            // the real response status, after the payload is sent
            if (!HttpResponseStatus.CONTINUE.equals(status)) {
                while (HttpResponseStatus.CONTINUE.equals(getStatus())) {
                if (states.contains(StateType.Timeout)) {
                    throw new AssertionError("Timed out");
                }
                    if (states.contains(StateType.Cancelled)) {
                        throw new AssertionError("Cancelled");
            }
                    HttpResponseStatus currStatus = getStatus();
                    if (currStatus == null || HttpResponseStatus.CONTINUE.equals(currStatus)) {
                        latches.get(HeadersReceived).reset();
                        await(HeadersReceived);
                    } else if (!HttpResponseStatus.CONTINUE.equals(currStatus)) {
                        break;
            }
                }
            }
            if (getStatus() == null) {
                if (states.contains(StateType.Timeout)) {
                    throw new AssertionError("Timed out");
                }
                if (states.contains(StateType.Cancelled)) {
                    throw new AssertionError("Cancelled");
                }
                System.out.println("AWAIT HEADERS RECEIVED AGAIN - states " + states);
                await(HeadersReceived);
            }
            HttpResponseStatus actualStatus = getStatus();
            assertNotNull("Status never sent, expected " + status, actualStatus);
            if (!status.equals(actualStatus)) {
                throw new AssertionError("Expected " + status + " got " + actualStatus + ": " + content());
            }
            return this;
        }

        @Override
        public CallResult throwIfError() throws Throwable {
            TestHarness.throwIfError();
            await(latches.get(StateType.Error));
            if (err != null) {
                throw err;
            }
            if (future != null) {
                future.throwIfError();
            }
            return this;
        }

        private String headersToString() {
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, String> e : getHeaders().entries()) {
                sb.append(e.getKey()).append(": ").append(e.getValue()).append("\n");
            }
            return sb.toString();
        }

        @Override
        public CallResult assertHasHeader(String name) throws Throwable {
            await(HeadersReceived);
            assertNotNull("Headers never sent", getHeaders());
            String val = getHeaders().get(name);
            assertNotNull("No value for '" + name + "' in \n" + headersToString(), val);
            return this;
        }

        public <T> CallResult assertHeader(HeaderValueType<T> hdr, T value) throws Throwable {
            waitForHeaders(hdr.name());
            assertNotNull("Headers never sent", getHeaders());
            String val = getHeaders().get(hdr.name());
            assertNotNull("No value for '" + hdr.name() + "' in \n" + headersToString(), val);
            T obj = hdr.toValue(val);
            assertEquals(value, obj);
            return this;
        }

        private HttpHeaders waitForHeaders(String lookingFor) throws InterruptedException {
            await(HeadersReceived);
            HttpHeaders h = getHeaders();
            if (h == null) {
                await(ContentReceived);
                h = getHeaders();
            } else {
                String s = h.get(lookingFor);
                if (s == null) {
                    await(ContentReceived);
                }
            }
            return h;
        }

        public <T> CallResult assertHeaderNotEquals(HeaderValueType<T> hdr, T value) throws Throwable {
            HttpHeaders h = waitForHeaders(hdr.name());
            assertNotNull("Headers never arrived", h);
            T obj = hdr.toValue(h.get(hdr.name()));
            assertNotEquals(value, obj);
            return this;
        }

        public <T> Iterable<T> getHeaders(HeaderValueType<T> hdr) throws InterruptedException {
            HttpHeaders h = waitForHeaders(hdr.name());
            List<String> all = h.getAll(hdr.name());
            List<T> result = null;
            if (all != null) {
                result = new ArrayList<>(all.size());
                for (String s : all) {
                    T obj = hdr.toValue(s);
                    result.add(obj);
                }
            }
            return result == null ? Collections.<T>emptySet() : result;
        }

        public <T> T getHeader(HeaderValueType<T> hdr) throws InterruptedException {
            HttpHeaders h = waitForHeaders(hdr.name());
            assertNotNull("Headers never sent", h);
            String result = h.get(hdr.name());
            if (result != null) {
                return hdr.toValue(result);
            }
            return null;
        }

        @Override
        public CallResult await() throws Throwable {
            await(FullContentReceived);
            return this;
        }

        @Override
        public <T> T content(Class<T> type) throws Throwable {
            await(FullContentReceived);
            assertHasContent();
            ByteBuf buf = getContent();
            assertTrue("Content not readable", buf.isReadable());
            getContent().resetReaderIndex();
            if (type == byte[].class) {
                byte[] b = new byte[buf.readableBytes()];
                buf.readBytes(b);
                return type.cast(b);
            } else if (type == ByteBuf.class) {
                return type.cast(buf);
            } else if (type == String.class || type == CharSequence.class) {
                byte[] b = new byte[buf.readableBytes()];
                buf.readBytes(b);
                return type.cast(new String(b, "UTF-8"));
            } else {
                ObjectMapper m = mapper;
                try {
                    return m.readValue(new ByteBufInputStream(buf), type);
                } catch (JsonParseException | JsonMappingException ex) {
                    buf.resetReaderIndex();
                    String data = bufToString(buf);
                    throw new IOException(ex.getMessage() + " - data: " + data, ex);
                }
            }
        }

        public <T> CallResult assertContentNotEquals(Class<T> type, T compareTo) throws Throwable {
            assertHasContent();
            T obj = this.content(type);
            if (obj != null && compareTo != null && obj.getClass().isArray() && compareTo.getClass().isArray()) {
                assertFalse("Should not be equal: " + Objects.toString(compareTo) + " and "
                        + Objects.toString(obj), Objects.equals(compareTo, obj));
            } else {
                assertNotEquals(compareTo, obj);
            }
            return this;
        }

        private String arrayToString(Object o) {
            StringBuilder sb = new StringBuilder();
            int count = Array.getLength(o);
            for (int i = 0; i < count; i++) {
                Object elem = Array.get(o, i);
                sb.append("" + elem);
                if (i != count - 1) {
                    sb.append(",");
                }
            }
            return sb.toString();
        }

        public <T> CallResult assertContent(Class<T> type, T compareTo) throws Throwable {
            assertHasContent();
            T obj = this.content(type);
            if (obj != null && compareTo != null && obj.getClass().isArray() && compareTo.getClass().isArray()) {
                assertTrue("Not equal: " + arrayToString(compareTo) + " and "
                        + arrayToString(obj), Objects.deepEquals(compareTo, obj));
            } else {
                assertEquals(compareTo, obj);
            }
            return this;
        }

        public <T> CallResult assertHasContent() throws Throwable {
            await(FullContentReceived);
            if (getContent() == null) {
                await(FullContentReceived);
            }
            assertNotNull("No content received", getContent());
            return this;
        }

        /**
         * @return the status
         */
        public HttpResponseStatus getStatus() {
            return status.get();
        }

        /**
         * @param status the status to set
         */
        public void setStatus(HttpResponseStatus status) {
            if (status == null) {
                return;
            }
            HttpResponseStatus st = getStatus();
            if (st != null) {
                if (status.code() > st.code()) {
                    this.status.set(status);
                }
            } else {
                this.status.set(status);
            }
        }

        /**
         * @return the headers
         */
        public HttpHeaders getHeaders() {
            return headers.get();
        }

        /**
         * @param headers the headers to set
         */
        public void setHeaders(HttpHeaders headers) {
            if (headers == null) {
                return;
            }
            HttpHeaders curr = getHeaders();
            if (curr != null) {
                DefaultHttpHeaders hdrs = new DefaultHttpHeaders();
                for (Map.Entry<String, String> e : headers) {
                    hdrs.add(e.getKey(), e.getValue());
                }
                for (Map.Entry<String, String> e : curr) {
                    hdrs.add(e.getKey(), e.getValue());
                }
                this.headers.set(hdrs);
            } else {
                this.headers.set(headers);
            }
        }

        private String bufToString(ByteBuf buf) {
            buf.resetReaderIndex();
            if (buf.readableBytes() <= 0) {
                return "[no bytes]";
            }
            byte[] b = new byte[buf.readableBytes()];
            buf.readBytes(b);
            return new String(b);
        }

        /**
         * @param content the content to set
         */
        public void setContent(ByteBuf content) {
            if (content == null) {
                return;
            }
            if (this.content.get() != null && log) {
//                throw new Error("Replace content? Old: " + bufToString(this.content.get())
//                        + " NEW " + bufToString(content));
                System.out.println("Replacing old content: " + bufToString(this.content.get()));
            }
            this.content.set(content);
        }

        public CallResult assertHasCookie(String name) throws Throwable {
            for (io.netty.handler.codec.http.cookie.Cookie ck : getHeaders(SET_COOKIE_B)) {
                if (name.equals(ck.name())) {
                    return this;
                }
            }
            fail("No cookie named '" + name + "' in " + getHeaders(Headers.SET_COOKIE_B));
            return this;
        }

        public CallResult assertCookieValue(String name, String val) throws Throwable {
            for (io.netty.handler.codec.http.cookie.Cookie ck : getHeaders(Headers.SET_COOKIE_B)) {
                if (name.equals(ck.name())) {
                    assertEquals(val, ck.value());
                }
            }
            return this;
        }

        @Override
        public Cookie getCookie(String cookieName) throws InterruptedException {
            HttpHeaders headers = getHeaders();
            for (String cookieHeader : headers.getAll(Headers.SET_COOKIE.name())) {
                Cookie cookie = Headers.SET_COOKIE.toValue(cookieHeader);
                if (cookie != null) {
                    if (cookieName.equals(cookie.getName())) {
                        return cookie;
                    }
                } else if (log) {
                        System.err.println("Found a cookie header that does not decode to a cookie: '" + cookieHeader + "'");
                    }
                }
            return null;
        }

        @Override
        public io.netty.handler.codec.http.cookie.Cookie getCookieB(String cookieName) throws InterruptedException {
            HttpHeaders headers = getHeaders();
            for (String cookieHeader : headers.getAll(Headers.SET_COOKIE_B.name())) {
                io.netty.handler.codec.http.cookie.Cookie cookie = Headers.SET_COOKIE_B.toValue(cookieHeader);
                if (cookieName.equals(cookie.name())) {
                    return cookie;
                }
            }
            return null;
        }

        @Override
        public String getCookieValue(String cookieName) throws InterruptedException {
            Cookie cookie = getCookie(cookieName);
            return cookie == null ? null : cookie.getValue();
        }

        @Override
        public CallResult assertHasHeader(HeaderValueType<?> name) throws Throwable {
            return assertHasHeader(name.name());
        }

        @Override
        public HttpResponseStatus status() {
            return status.get();
        }
    }

    public interface CallResult {

        CallResult assertCookieValue(String name, String value) throws Throwable;

        CallResult assertHasCookie(String name) throws Throwable;

        CallResult assertStateSeen(StateType type) throws Throwable;

        CallResult assertContentContains(String expected) throws Throwable;

        CallResult assertContent(String expected) throws Throwable;

        CallResult assertCode(int code) throws Throwable;

        CallResult assertStatus(HttpResponseStatus status) throws Throwable;

        CallResult throwIfError() throws Throwable;

        io.netty.handler.codec.http.cookie.Cookie getCookieB(String cookieName) throws InterruptedException;

        CallResult assertTimedOut() throws Throwable;

        <T> CallResult assertHeader(HeaderValueType<T> hdr, T value) throws Throwable;

        <T> CallResult assertHeaderNotEquals(HeaderValueType<T> hdr, T value) throws Throwable;

        CallResult await() throws Throwable;

        String content() throws UnsupportedEncodingException, InterruptedException;

        <T> T content(Class<T> type) throws Throwable;

        void cancel();

        StateType state();

        CallResult assertHasHeader(String name) throws Throwable;

        CallResult assertHasHeader(HeaderValueType<?> name) throws Throwable;

        <T> T getHeader(HeaderValueType<T> hdr) throws InterruptedException;

        <T> CallResult assertContent(Class<T> type, T compareTo) throws Throwable;

        <T> CallResult assertContentNotEquals(Class<T> type, T compareTo) throws Throwable;

        <T> CallResult assertHasContent() throws Throwable;

        <T> Iterable<T> getHeaders(HeaderValueType<T> hdr) throws Throwable;

        Cookie getCookie(String cookieName) throws Throwable;

        String getCookieValue(String cookieName) throws Throwable;

        HttpResponseStatus status();
    }

    private static class NamedLatch extends ResettableCountDownLatch {

        private ThreadLocal<Boolean> inCountDown = new ThreadLocal<>();
        private final String name;
        NamedLatch[] others;

        public NamedLatch(String name, NamedLatch... others) {
            super(1);
            this.name = name;
            this.others = others;
        }

        @Override
        public void countDown() {
            Boolean alreadyInCountDown = inCountDown.get();
            if (Boolean.TRUE.equals(alreadyInCountDown)) {
                return;
            }
            inCountDown.set(true);
            try {
                super.countDown();
                for (ResettableCountDownLatch latch : others) {
                    if (latch != this) {
                        latch.countDown();
                    }
                }
            } finally {
                inCountDown.set(false);
            }
        }

        @Override
        public void await() throws InterruptedException {
            String old = Thread.currentThread().getName();
            String threadName = "Waiting " + this + " (was " + old + ")";
            Thread.currentThread().setName(threadName);
            try {
                super.await();
            } finally {
                Thread.currentThread().setName(old);
            }
        }

        @Override
        public boolean await(long l, TimeUnit tu) throws InterruptedException {
            String old = Thread.currentThread().getName();
            String threadName = "Waiting " + this + " (was " + old + ")";
            Thread.currentThread().setName(threadName);
            try {
                return super.await(l, tu);
            } finally {
                Thread.currentThread().setName(old);
            }
        }

        public String toString() {
            return name + " (" + getCount() + ")";
        }
    }

    private static class Fake implements Server, ServerControl {

        private final int port;

        Fake(int port) {
            this.port = port;
        }

        @Override
        public int getPort() {
            return port;
        }

        @Override
        public ServerControl start() throws IOException {
            return this;
        }

        @Override
        public ServerControl start(int port) throws IOException {
            return this;
        }

        @Override
        public ServerControl start(boolean ssl) throws IOException {
            return this;
        }

        @Override
        public ServerControl start(int port, boolean ssl) throws IOException {
            return this;
        }

        @Override
        public void shutdown(boolean immediately, long timeout, TimeUnit unit) throws InterruptedException {
            //do nothing
        }

        @Override
        public void shutdown(boolean immediately) throws InterruptedException {
            //do nothing
        }

        @Override
        public void await() throws InterruptedException {
            synchronized (this) {
                wait();
            }
        }

        @Override
        public void awaitUninterruptibly() {
            synchronized (this) {
                for (;;) {
                    try {
                        wait();
                        return;
                    } catch (InterruptedException ex) {
                        org.openide.util.Exceptions.printStackTrace(ex);
                    }
                }
            }
        }

        @Override
        public long awaitNanos(long l) throws InterruptedException {
            //do nothing
            return 0L;
        }

        @Override
        public boolean await(long l, TimeUnit tu) throws InterruptedException {
            return true;
        }

        @Override
        public boolean awaitUntil(Date date) throws InterruptedException {
            return true;
        }

        @Override
        public void signal() {
            synchronized (this) {
                notifyAll();
            }
        }

        @Override
        public void signalAll() {
            synchronized (this) {
                notifyAll();
            }
        }
    }
}
