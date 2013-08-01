/*
 *      Copyright (C) 2012 DataStax Inc.
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */
package com.datastax.driver.core;

import com.datastax.driver.core.exceptions.AuthenticationException;
import com.datastax.driver.core.exceptions.DriverInternalError;
import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.Uninterruptibles;
import org.apache.cassandra.service.ClientState;
import org.apache.cassandra.transport.Frame;
import org.apache.cassandra.transport.Message;
import org.apache.cassandra.transport.messages.CredentialsMessage;
import org.apache.cassandra.transport.messages.ErrorMessage;
import org.apache.cassandra.transport.messages.QueryMessage;
import org.apache.cassandra.transport.messages.StartupMessage;
import org.jboss.netty.bootstrap.ClientBootstrap;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelFactory;
import org.jboss.netty.channel.ChannelFuture;
import org.jboss.netty.channel.ChannelFutureListener;
import org.jboss.netty.channel.ChannelHandler;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.ChannelPipeline;
import org.jboss.netty.channel.ChannelPipelineFactory;
import org.jboss.netty.channel.ChannelStateEvent;
import org.jboss.netty.channel.Channels;
import org.jboss.netty.channel.ExceptionEvent;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.channel.SimpleChannelUpstreamHandler;
import org.jboss.netty.channel.group.ChannelGroup;
import org.jboss.netty.channel.group.ChannelGroupFuture;
import org.jboss.netty.channel.group.DefaultChannelGroup;
import org.jboss.netty.channel.socket.nio.NioClientSocketChannelFactory;
import org.jboss.netty.handler.ssl.SslHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLEngine;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Iterator;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

// For LoggingHandler
//import org.jboss.netty.handler.logging.LoggingHandler;
//import org.jboss.netty.logging.InternalLogLevel;

/**
 * A connection to a Cassandra Node.
 */
class Connection extends org.apache.cassandra.transport.Connection {
    public static final int MAX_STREAM_PER_CONNECTION = 128;

    private static final Logger logger = LoggerFactory.getLogger(Connection.class);

    // TODO: that doesn't belong here
    private static final String CQL_VERSION = "3.0.0";

    private static final org.apache.cassandra.transport.Connection.Tracker EMPTY_TRACKER = new org.apache.cassandra.transport.Connection.Tracker() {
        @Override
        public void addConnection(Channel ch, org.apache.cassandra.transport.Connection connection) {
        }

        @Override
        public void closeAll() {
        }
    };

    // Used by connnection pooling to count how many requests are "in flight" on that connection.
    public final AtomicInteger inFlight = new AtomicInteger(0);

    private final AtomicInteger writer = new AtomicInteger(0);

    private volatile boolean isClosed;
    private volatile String keyspace;

    private volatile boolean isDefunct;
    private volatile ConnectionException exception;
    private final Dispatcher dispatcher;
    private final Factory factory;
    private final String name;
    protected final InetAddress address;
    //channel must be overridden and getter as well
    private Channel channel;

    /**
     * Create a new connection to a Cassandra node.
     */
    public Connection(String name, InetAddress address, Dispatcher dispatcher, Factory factory) {
        super(null, Frame.Header.CURRENT_VERSION, EMPTY_TRACKER);
        this.name = name;
        this.address = address;
        this.dispatcher = dispatcher;
        this.factory = factory;
    }

    public boolean isDefunct() {
        return isDefunct;
    }

    public ConnectionException lastException() {
        return exception;
    }

    public String keyspace() {
        return keyspace;
    }

    public void setKeyspace(String keyspace) throws ConnectionException {
        if (keyspace == null)
            return;

        if (this.keyspace != null && this.keyspace.equals(keyspace))
            return;

        try {
            logger.trace("[{}] Setting keyspace {}", name, keyspace);
            // Note: we quote the keyspace below, because the name is the one coming from Cassandra, so it's in the right case already
            long timeout = factory.getConnectTimeoutMillis();
            Future future = write(new QueryMessage("USE \"" + keyspace + "\"", ConsistencyLevel.DEFAULT_CASSANDRA_CL));
            Message.Response response = Uninterruptibles.getUninterruptibly(future, timeout, TimeUnit.MILLISECONDS);
            switch (response.type) {
                case RESULT:
                    this.keyspace = keyspace;
                    break;
                default:
                    // The code set the keyspace only when a successful 'use'
                    // has been perform, so there shouldn't be any error here.
                    // It can happen however that the node we're connecting to
                    // is not up on the schema yet. In that case, defuncting
                    // the connection is not a bad choice.
                    defunct(new ConnectionException(address, String.format("Problem while setting keyspace, got %s as response", response)));
                    break;
            }
        } catch (ConnectionException e) {
            throw defunct(e);
        } catch (TimeoutException e) {
            logger.warn(String.format("Timeout while setting keyspace on connection to %s. This should not happen but is not critical (it will retried)", address));
        } catch (BusyConnectionException e) {
            logger.warn(String.format("Tried to set the keyspace on busy connection to %s. This should not happen but is not critical (it will retried)", address));
        } catch (ExecutionException e) {
            throw defunct(new ConnectionException(address, "Error while setting keyspace", e));
        }
    }

    /**
     * Write a request on this connection.
     *
     * @param request the request to send
     * @return a future on the server response
     * @throws ConnectionException if the connection is closed
     * @throws TransportException  if an I/O error while sending the request
     */
    public Future write(Message.Request request) throws ConnectionException, BusyConnectionException {
        Future future = new Future(request);
        write(future);
        return future;
    }

    public void write(ResponseCallback callback) throws ConnectionException, BusyConnectionException {

        Message.Request request = callback.request();

        request.attach(this);

        ResponseHandler handler = new ResponseHandler(dispatcher, callback);
        dispatcher.add(handler);
        request.setStreamId(handler.streamId);

        /*
         * We check for close/defunct *after* having set the handler because closing/defuncting
         * will set their flag and then error out handler if need. So, by doing the check after
         * having set the handler, we guarantee that even if we race with defunct/close, we may
         * never leave a handler that won't get an answer or be errored out.
         */
        if (isDefunct) {
            dispatcher.removeHandler(handler.streamId);
            throw new ConnectionException(address, "Write attempt on defunct connection");
        }

        if (isClosed) {
            dispatcher.removeHandler(handler.streamId);
            throw new ConnectionException(address, "Connection has been closed");
        }

        logger.trace("[{}] writing request {}", name, request);
        writer.incrementAndGet();
        channel().write(request).addListener(writeHandler(request, handler));
    }

    public void close() {
        try {
            close(0, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public boolean close(long timeout, TimeUnit unit) throws InterruptedException {
        if (isClosed)
            return true;

        // Note: there is no guarantee only one thread will reach that point, but executing this
        // method multiple time is harmless. If the latter change, we'll have to CAS isClosed to
        // make sure this gets executed only once.

        logger.trace("[{}] closing connection", name);

        // Make sure all new writes are rejected
        isClosed = true;

        long start = System.nanoTime();
        if (!isDefunct) {
            // Busy waiting, we just wait for request to be fully written, shouldn't take long
            while (writer.get() > 0 && Cluster.timeSince(start, unit) < timeout)
                Uninterruptibles.sleepUninterruptibly(1, unit);
        }
        // Note: we must not call releaseExternalResources on the bootstrap, because this shutdown the executors, which are shared
        boolean closed = channel() == null // This method can be throw in the ctor at which point channel is not yet set. This is ok.
                ? true
                : channel().close().await(timeout - Cluster.timeSince(start, unit), unit);

        // We've closed the channel. If anyone was waiting on that connection, we should defunct it otherwise it'll wait forever.
        // Note that this is a no-op if there is no handler set anymore.
        dispatcher.errorOutAllHandler(this, new TransportException(address, "Connection has been closed"));
        return closed;
    }

    public boolean isClosed() {
        return isClosed;
    }

    @Override
    public Channel channel() {
        return channel;
    }

    @Override
    public String toString() {
        return String.format("Connection[%s, inFlight=%d, closed=%b]", name, inFlight.get(), isClosed);
    }

    // Cruft needed because we reuse server side classes, but we don't care about it
    public void validateNewMessage(Message.Type type) {
    }

    public void applyStateTransition(Message.Type requestType, Message.Type responseType) {
    }


    public ClientState clientState() {
        return null;
    }

    private void initializeTransport(Channel channel) throws ConnectionException, InterruptedException {
        this.channel = channel;

        // TODO: we will need to get fancy about handling protocol version at
        // some point, but keep it simple for now.
        ImmutableMap.Builder<String, String> options = new ImmutableMap.Builder<String, String>();
        options.put(StartupMessage.CQL_VERSION, CQL_VERSION);
        ProtocolOptions.Compression compression = factory.configuration.getProtocolOptions().getCompression();
        if (compression != ProtocolOptions.Compression.NONE) {
            options.put(StartupMessage.COMPRESSION, compression.toString());
            setCompressor(compression.compressor());
        }
        StartupMessage startup = new StartupMessage(options.build());
        try {
            Message.Response response = write(startup).get();
            switch (response.type) {
                case READY:
                    break;
                case ERROR:
                    throw defunct(new TransportException(address, String.format("Error initializing connection: %s", ((ErrorMessage) response).error.getMessage())));
                case AUTHENTICATE:
                    CredentialsMessage creds = new CredentialsMessage();
                    creds.credentials.putAll(factory.authProvider.getAuthInfo(address));
                    Message.Response authResponse = write(creds).get();
                    switch (authResponse.type) {
                        case READY:
                            break;
                        case ERROR:
                            throw new AuthenticationException(address, (((ErrorMessage) authResponse).error).getMessage());
                        default:
                            throw defunct(new TransportException(address, String.format("Unexpected %s response message from server to a CREDENTIALS message", authResponse.type)));
                    }
                    break;
                default:
                    throw defunct(new TransportException(address, String.format("Unexpected %s response message from server to a STARTUP message", response.type)));
            }
        } catch (BusyConnectionException e) {
            throw new DriverInternalError("Newly created connection should not be busy");
        } catch (ExecutionException e) {
            throw defunct(new ConnectionException(address, String.format("Unexpected error during transport initialization (%s)", e.getCause()), e.getCause()));
        }
    }

    private ChannelFutureListener writeHandler(final Message.Request request, final ResponseHandler handler) {
        return new ChannelFutureListener() {
            @Override
            public void operationComplete(ChannelFuture writeFuture) {

                writer.decrementAndGet();

                if (!writeFuture.isSuccess()) {

                    logger.debug("[{}] Error writing request {}", name, request);
                    // Remove this handler from the dispatcher so it don't get notified of the error
                    // twice (we will fail that method already)
                    dispatcher.removeHandler(handler.streamId);

                    ConnectionException ce;
                    if (writeFuture.getCause() instanceof java.nio.channels.ClosedChannelException) {
                        ce = new TransportException(address, "Error writing: Closed channel");
                    } else {
                        ce = new TransportException(address, "Error writing", writeFuture.getCause());
                    }
                    handler.callback.onException(Connection.this, defunct(ce));
                } else {
                    logger.trace("[{}] request sent successfully", name);
                }
            }
        };
    }

    ConnectionException defunct(ConnectionException e) {
        if (logger.isDebugEnabled())
            logger.debug("Defuncting connection to " + address, e);
        exception = e;
        isDefunct = true;
        dispatcher.errorOutAllHandler(this, e);
        close();
        return e;
    }

    private static String extractMessage(Throwable t) {
        String msg = t == null || t.getMessage() == null || t.getMessage().isEmpty()
                ? t.toString()
                : t.getMessage();
        return " (" + msg + ")";
    }

    public static class Factory {

        private final ExecutorService bossExecutor = Executors.newCachedThreadPool();
        private final ExecutorService workerExecutor = Executors.newCachedThreadPool();

        private final ChannelFactory channelFactory = new NioClientSocketChannelFactory(bossExecutor, workerExecutor);
        private final ChannelGroup allChannels = new DefaultChannelGroup();

        private final ConcurrentMap<Host, AtomicInteger> idGenerators = new ConcurrentHashMap<Host, AtomicInteger>();
        public final DefaultResponseHandler defaultHandler;
        public final Configuration configuration;

        public final AuthInfoProvider authProvider;
        private volatile boolean isShutdown;

        public Factory(Cluster.Manager manager, AuthInfoProvider authProvider) {
            this(manager, manager.configuration, authProvider);
        }

        private Factory(DefaultResponseHandler defaultHandler, Configuration configuration, AuthInfoProvider authProvider) {
            this.defaultHandler = defaultHandler;
            this.configuration = configuration;
            this.authProvider = authProvider;
        }

        /**
         * Opens a new connection to the node this factory points to.
         *
         * @return the newly created (and initialized) connection.
         * @throws ConnectionException if connection attempt fails.
         */
        public Connection open(Host host) throws ConnectionException, InterruptedException {
            InetAddress address = host.getAddress();

            if (isShutdown)
                throw new ConnectionException(address, "Connection factory is shut down");

            String name = address.toString() + "-" + getIdGenerator(host).getAndIncrement();
            ClientBootstrap bootstrap = newBootstrap();
            Dispatcher dispatcher = new Dispatcher();
            Connection connectionInstance = new Connection(name, address, dispatcher, this);
            if (configuration.getProtocolOptions().sslOptions == null)
                bootstrap.setPipelineFactory(new PipelineFactory(connectionInstance, dispatcher));
            else
                bootstrap.setPipelineFactory(new SecurePipelineFactory(connectionInstance, dispatcher, configuration.getProtocolOptions().sslOptions));

            ChannelFuture future = bootstrap.connect(new InetSocketAddress(address, getPort()));

            // Wait until the connection attempt succeeds or fails.
            Channel channel = future.awaitUninterruptibly().getChannel();
            allChannels.add(channel);
            if (!future.isSuccess()) {
                if (logger.isDebugEnabled())
                    logger.debug(String.format("[%s] Error connecting to %s%s", name, address, extractMessage(future.getCause())));
                throw new TransportException(address, "Cannot connect", future.getCause());
            }
            logger.trace("[{}] Connection opened successfully", name);
            connectionInstance.initializeTransport(channel);
            logger.trace("[{}] Transport initialized and ready", name);
            return connectionInstance;
        }

        public boolean shutdown(long timeout, TimeUnit unit) throws InterruptedException {
            // Make sure we skip creating connection from now on.
            isShutdown = true;

            long start = System.nanoTime();
            ChannelGroupFuture future = allChannels.close();

            channelFactory.releaseExternalResources();

            return future.await(timeout, unit)
                    && bossExecutor.awaitTermination(timeout - Cluster.timeSince(start, unit), unit)
                    && workerExecutor.awaitTermination(timeout - Cluster.timeSince(start, unit), unit);
        }

        public long getConnectTimeoutMillis() {
            return configuration.getSocketOptions().getConnectTimeoutMillis();
        }

        public int getPort() {
            return configuration.getProtocolOptions().getPort();
        }

        private AtomicInteger getIdGenerator(Host host) {
            AtomicInteger g = idGenerators.get(host);
            if (g == null) {
                g = new AtomicInteger(1);
                AtomicInteger old = idGenerators.putIfAbsent(host, g);
                if (old != null)
                    g = old;
            }
            return g;
        }

        private ClientBootstrap newBootstrap() {
            ClientBootstrap b = new ClientBootstrap(channelFactory);

            SocketOptions options = configuration.getSocketOptions();

            b.setOption("connectTimeoutMillis", options.getConnectTimeoutMillis());
            Boolean keepAlive = options.getKeepAlive();
            if (keepAlive != null)
                b.setOption("keepAlive", keepAlive);
            Boolean reuseAddress = options.getReuseAddress();
            if (reuseAddress != null)
                b.setOption("reuseAddress", reuseAddress);
            Integer soLinger = options.getSoLinger();
            if (soLinger != null)
                b.setOption("soLinger", soLinger);
            Boolean tcpNoDelay = options.getTcpNoDelay();
            if (tcpNoDelay != null)
                b.setOption("tcpNoDelay", tcpNoDelay);
            Integer receiveBufferSize = options.getReceiveBufferSize();
            if (receiveBufferSize != null)
                b.setOption("receiveBufferSize", receiveBufferSize);
            Integer sendBufferSize = options.getSendBufferSize();
            if (sendBufferSize != null)
                b.setOption("sendBufferSize", sendBufferSize);

            return b;
        }

    }

    private static Connection getConnection(Channel channel) {
        return (Connection) channel.getAttachment();
    }

    private static class Dispatcher extends SimpleChannelUpstreamHandler {

        public final StreamIdGenerator streamIdHandler = new StreamIdGenerator();
        private final ConcurrentMap<Integer, ResponseHandler> pending = new ConcurrentHashMap<Integer, ResponseHandler>();

        public void add(ResponseHandler handler) {
            ResponseHandler old = pending.put(handler.streamId, handler);
            assert old == null;
        }

        public void removeHandler(int streamId) {
            pending.remove(streamId);
            streamIdHandler.release(streamId);
        }

        @Override
        public void messageReceived(ChannelHandlerContext ctx, MessageEvent e) {
            Connection connection = getConnection(ctx.getChannel());
            if (!(e.getMessage() instanceof Message.Response)) {
                logger.error("[{}] Received unexpected message: {}", connection.name, e.getMessage());
                connection.defunct(new TransportException(connection.address, "Unexpected message received: " + e.getMessage()));
            } else {
                Message.Response response = (Message.Response) e.getMessage();
                int streamId = response.getStreamId();

                logger.trace("[{}] received: {}", connection.name, e.getMessage());

                if (streamId < 0) {
                    connection.factory.defaultHandler.handle(response);
                    return;
                }

                ResponseHandler handler = pending.remove(streamId);
                streamIdHandler.release(streamId);
                if (handler == null) {
                    if (!connection.isDefunct()) {
                        /*
                         * In general, a defunct connection is one that is broken, in which case we won't receive a message anymore.
                         * However, we could be defunct because of a internal error (anecdotally, this will happens if you have
                         * an old version of the google-collections (the guava precursor) in the classpath, which happened to at least
                         * 2 users already). In that case, the initial defunct already has registered what the problem was and we can ignore this.
                         *
                         * But if the connection is not defunct, this is a bug, either of us or cassandra. So log it, but I'm not sure it's worth
                         * breaking the connection for that.
                         */
                        logger.error("[{}] No handler set for stream {} (this is a bug, either of this driver or of Cassandra, you should report it). Received message is {}",
                                connection.name, streamId, response);
                    }
                    return;
                }
                handler.callback.onSet(connection, response);
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, ExceptionEvent e) {
            Connection connection = getConnection(ctx.getChannel());
            if (logger.isTraceEnabled())
                logger.trace(String.format("[%s] connection error", connection.name), e.getCause());

            // Ignore exception while writing, this will be handled by write() directly
            if (connection.writer.get() > 0)
                return;

            connection.defunct(new TransportException(connection.address, String.format("Unexpected exception triggered (%s)", e.getCause()), e.getCause()));
        }

        public void errorOutAllHandler(Connection connection, ConnectionException ce) {
            Iterator<ResponseHandler> iter = pending.values().iterator();
            while (iter.hasNext()) {
                iter.next().callback.onException(connection, ce);
                iter.remove();
            }
        }

        @Override
        public void channelClosed(ChannelHandlerContext ctx, ChannelStateEvent e) {
            Connection connection = getConnection(ctx.getChannel());
            // If we've closed the channel server side then we don't really want to defunct the connection, but
            // if there is remaining thread waiting on us, we still want to wake them up
            if (connection.isClosed)
                errorOutAllHandler(connection, new TransportException(connection.address, "Channel has been closed"));
            else
                connection.defunct(new TransportException(connection.address, "Channel has been closed"));
        }
    }

    static class Future extends SimpleFuture<Message.Response> implements RequestHandler.Callback {

        private final Message.Request request;
        private volatile InetAddress address;

        public Future(Message.Request request) {
            this.request = request;
        }

        @Override
        public Message.Request request() {
            return request;
        }

        @Override
        public void onSet(Connection connection, Message.Response response, ExecutionInfo info) {
            onSet(connection, response);
        }

        @Override
        public void onSet(Connection connection, Message.Response response) {
            this.address = connection.address;
            super.set(response);
        }

        @Override
        public void onException(Connection connection, Exception exception) {
            super.setException(exception);
        }

        public InetAddress getAddress() {
            return address;
        }
    }

    interface ResponseCallback {
        public Message.Request request();

        public void onSet(Connection connection, Message.Response response);

        public void onException(Connection connection, Exception exception);
    }

    private static class ResponseHandler {

        public final int streamId;
        public final ResponseCallback callback;

        public ResponseHandler(Dispatcher dispatcher, ResponseCallback callback) throws BusyConnectionException {
            this.streamId = dispatcher.streamIdHandler.next();
            this.callback = callback;
        }
    }

    public interface DefaultResponseHandler {
        public void handle(Message.Response response);
    }

    private static class PipelineFactory implements ChannelPipelineFactory {
        // Stateless handlers
        private static final Message.ProtocolDecoder messageDecoder = new Message.ProtocolDecoder();
        private static final Message.ProtocolEncoder messageEncoder = new Message.ProtocolEncoder();
        private static final Frame.Decompressor frameDecompressor = new Frame.Decompressor();
        private static final Frame.Compressor frameCompressor = new Frame.Compressor();
        private static final Frame.Encoder frameEncoder = new Frame.Encoder();
        private final Connection connection;
        private final ChannelHandler dispatcher;

        private PipelineFactory(Connection connection, ChannelHandler dispatcher) {
            this.connection = connection;
            this.dispatcher = dispatcher;
        }

        @Override
        public ChannelPipeline getPipeline() throws Exception {
            ChannelPipeline pipeline = Channels.pipeline();

            //pipeline.addLast("debug", new LoggingHandler(InternalLogLevel.INFO));

            pipeline.addLast("frameDecoder", new Frame.Decoder(new org.apache.cassandra.transport.Connection.Factory() {
                @Override
                public org.apache.cassandra.transport.Connection newConnection(Channel channel, int version) {
                    return connection;
                }
            }));
            pipeline.addLast("frameEncoder", frameEncoder);

            pipeline.addLast("frameDecompressor", frameDecompressor);
            pipeline.addLast("frameCompressor", frameCompressor);

            pipeline.addLast("messageDecoder", messageDecoder);
            pipeline.addLast("messageEncoder", messageEncoder);

            pipeline.addLast("dispatcher", dispatcher);

            return pipeline;
        }
    }

    private static class SecurePipelineFactory extends PipelineFactory {

        private final SSLOptions options;

        public SecurePipelineFactory(Connection connection, ChannelHandler dispatcher, SSLOptions options) {
            super(connection, dispatcher);
            this.options = options;
        }

        public ChannelPipeline getPipeline() throws Exception {
            SSLEngine engine = options.context.createSSLEngine();
            engine.setUseClientMode(true);
            engine.setEnabledCipherSuites(options.cipherSuites);
            ChannelPipeline pipeline = super.getPipeline();
            pipeline.addFirst("ssl", new SslHandler(engine));
            return pipeline;
        }
    }
}
