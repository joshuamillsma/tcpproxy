package com.joshmills;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author jmills
 * @since April 2020
 * 
 * This is a simple Java NIO based TCP proxy.
 * It will listen on the specified port, and forward all traffic to the specified host and port
 */
public class TcpProxy
{
    private static final Logger m_logger = LoggerFactory.getLogger(TcpProxy.class);
    
    /**
     * command line options
     */
    @Option(name = "-l", usage = "Listen port")
    private int m_listenPort = 8443;

    @Option(name = "-h", usage = "Destination host")
    private String m_destination = "api.giphy.com";

    @Option(name = "-p", usage = "Destination port")
    private int m_destinationPort = 443;

    @Option(name = "-s", usage = "Select loop timeout in ms", hidden = true)
    private int m_selectInterval = 25;

    @Option(name = "-c", usage = "Downstream connect timeout in ms", hidden = true)
    private int m_connectTimeout = 500;

    @Option(name = "-b", usage = "Bytes to read", hidden = true)
    private int m_bufferSize = 8192;
    
    /**
     * Track timeouts for backend connections that haven't finished connecting
     */
    private final Set<TcpConnectionData> m_connecting = new HashSet<>();
    
    private void run() throws IOException
    {
        // this should automatically choose epoll on Linux final Selector

        // if any of this code blows up while we are binding the listen port, just
        // let it percolate out, since we are dead in the water anyway
        final Selector selector = Selector.open();
        final ServerSocketChannel serverSocket = ServerSocketChannel.open();

        // make sure we don't run out of TCP connections if they are stuck in time-wait
        serverSocket.setOption(StandardSocketOptions.SO_REUSEADDR, Boolean.TRUE);
        serverSocket.configureBlocking(false);
        serverSocket.register(selector, SelectionKey.OP_ACCEPT);

        // start listening, bind to all interfaces
        serverSocket.bind(new InetSocketAddress(m_listenPort));

        System.err.println("Started proxy on port " + m_listenPort);
        System.err.println("Press Ctrl+C to terminate");
        
        while (true)
        {
            // wait up to m_selectInterval to avoid busy-loop when there is nothing to do
            selector.select(m_selectInterval);
            
            final Set<SelectionKey> selectedKeys = selector.selectedKeys();
            final Iterator<SelectionKey> iter = selectedKeys.iterator();
            while (iter.hasNext())
            {
                final SelectionKey key = iter.next();                
                iter.remove();

                if (!key.isValid())
                {
                    continue;
                }
                else if (key.isConnectable())
                {
                    handleConnect(key);
                }
                else if (key.isAcceptable())
                {
                    handleAccept(selector, serverSocket);
                }
                
                if (key.isValid() && key.isWritable())
                {
                    handleWrite(key);
                }
                
                if (key.isValid() && key.isReadable())
                {
                    handleRead(key);
                }
            }

            // deal with connections that timed out
            final long now = System.currentTimeMillis();
            for (TcpConnectionData data : m_connecting)
            {
                if (data.getConnectionDeadline() < now)
                {
                    m_logger.error("Failed to connect to " + m_destination + ":" + m_destinationPort + " within " + m_connectTimeout + " ms");
                    m_connecting.remove(data);

                    // ideally we would pretend to have a connect timeout here
                    // closing the connection will have a similar effect
                    data.closeBothChannels();
                }
            }
        }
    }

    private void handleConnect(final SelectionKey key)
    {
        try
        {
            ((SocketChannel) key.channel()).finishConnect();
            m_logger.debug("Connected to " + m_destination + ":" + m_destinationPort);
            
            // no longer interest in connect
            key.interestOps(key.interestOps() & ~SelectionKey.OP_CONNECT);
            
            // no need to track timeout anymore
            m_connecting.remove(key.attachment());
        }
        catch (IOException ex)
        {
            // timeout will clear out the actual caller
            m_logger.error("finishConnect failed ", ex);
        }
    }
    
    private void handleRead(SelectionKey key)
    {
        final SocketChannel channel = (SocketChannel) key.channel();
        final TcpConnectionData data = (TcpConnectionData)key.attachment();
        
        // TODO: maybe we should keep around a pool of these, though modern garbage
        // collection does a pretty good job of handling short lived objects.
        final ByteBuffer buff = ByteBuffer.allocate(m_bufferSize);

        try
        {
            final int read = channel.read(buff);
            if (read == -1)
            {
                // Channel is closed, cancel the key and close the other side
                // TLSv3 allows for half-closed sockets, but lets not worry about that for now
                data.closeBothChannels();
            }
            else 
            {
                m_logger.debug("Read " + read + " bytes");
                data.queueSendToOtherSocket((ByteBuffer)buff.flip());
            }
        }
        catch (IOException ex)
        {
            // This shouldn't normally happen
            m_logger.debug("Read failed: ", ex);
            data.closeBothChannels();
        }
    }
    
    private void handleWrite(SelectionKey key)
    {
        ((TcpConnectionData)key.attachment()).sendQueuedData();
    }

    private void handleAccept(Selector selector, ServerSocketChannel serverSocket)
    {
        SocketChannel client = null;
        SocketChannel backend = null;
        TcpConnectionData data = null;
        TcpConnectionData other = null;
        
        try
        {
            client = serverSocket.accept();
            configureSocketChannel(client);

            backend = SocketChannel.open();
            configureSocketChannel(backend);
            backend.connect(new InetSocketAddress(m_destination, m_destinationPort));

            // use OP_WRITE rather than blocking write.  This will let one thread handle many concurrent connections.
            data = new TcpConnectionData(client.register(selector, SelectionKey.OP_READ | SelectionKey.OP_WRITE));
            
            // use OP_CONNECT since it is highly unlikely that the connect already succeeded
            // That would really only happen if we were connecting to localhost
            other = new TcpConnectionData(backend.register(selector, SelectionKey.OP_CONNECT | SelectionKey.OP_READ | SelectionKey.OP_WRITE));
            m_connecting.add(other);
            
            // track connection timeout
            other.setConnectionTimeout(m_connectTimeout);
            
            // cross link the sockets
            data.setBackend(other);
            other.setBackend(data);
        }
        catch (IOException ex)
        {
            if (other != null)
            {
                m_connecting.remove(other);
            }            
            TcpConnectionData.closeQuietly(client);
            TcpConnectionData.closeQuietly(backend);
            return;
        }
    }
    
    private void configureSocketChannel(SocketChannel channel) throws IOException
    {
        // non-blocking
        channel.configureBlocking(false);

        // turn off nagle
        channel.setOption(StandardSocketOptions.TCP_NODELAY, true);

        // reuse ports stuck in time-wait, etc
        channel.setOption(StandardSocketOptions.SO_REUSEADDR, true);
    }
    
    public static void main(String[] args) throws IOException
    {
        final TcpProxy proxy = new TcpProxy();
        final CmdLineParser parser = new CmdLineParser(proxy);

        try
        {
            // do some sanity checking on the incoming args
            parser.parseArgument(args);
            if (proxy.m_listenPort > Short.MAX_VALUE || proxy.m_listenPort < 1)
            {
                throw new CmdLineException("Invalid listen port");
            }
            if (proxy.m_destinationPort > Short.MAX_VALUE || proxy.m_destinationPort < 1)
            {
                throw new CmdLineException("Invalid destination port");
            }

            try
            {
                InetAddress.getByName(proxy.m_destination);
            }
            catch (UnknownHostException e)
            {
                throw new CmdLineException("Could not resolve hostname " + proxy.m_destination);
            }
        }
        catch (CmdLineException e)
        {
            System.err.println("Failed to start TcpProxy");
            System.err.println(e.getMessage());
            System.err.println();
            System.err.println("Usage: ");
            parser.printUsage(System.err);
            System.err.println();
            return;
        }

        proxy.run();
    }
}
