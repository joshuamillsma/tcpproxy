package com.joshmills;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.LinkedList;
import java.util.Queue;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author jmills
 * @since April 2020
 * 
 * Keep track of relevant connection data
 */
/* package protected */ class TcpConnectionData 
{
    private static final Logger m_logger = LoggerFactory.getLogger(TcpProxy.class);
    
    private final SelectionKey m_key;
    private long m_connectDeadline;
    private TcpConnectionData m_otherData;

    // queue up writes so that we don't block
    private final Queue<ByteBuffer> m_toSend = new LinkedList<>();
    private boolean m_closeAfterWrite = false;
 
    public TcpConnectionData(SelectionKey key)
    {
        this.m_key = key;
        key.attach(this);
    }
    
    public int queueSendToOtherSocket(final ByteBuffer bb)
    {
        m_otherData.m_toSend.add(bb);
        
        // flip the bit so that we get notified when we can write
        m_otherData.interestOpsSet(SelectionKey.OP_WRITE);
        return m_otherData.m_toSend.size();
    }
    
    public long getConnectionDeadline()
    {
        return m_connectDeadline;
    }
    
    public void setConnectionTimeout(int ms)
    {
        m_connectDeadline = System.currentTimeMillis() + ms;   
    }
    
    /**
     * link to connection we are forwarding data to 
     */
    public void setBackend(TcpConnectionData otherData)
    {
        m_otherData = otherData;
    }
    
    public void interestOpsSet(int ops)
    {
        if (m_key.isValid())
        {
            m_key.interestOps(m_key.interestOps() | ops);
        }
    }

    public void interestOpsClear(int ops)
    {
        if (m_key.isValid())
        {
            m_key.interestOps(m_key.interestOps() & ~ops);
        }
    }
    
    public void sendQueuedData()
    {
        try
        {
            final SocketChannel channel = (SocketChannel)m_key.channel();
            while (!m_toSend.isEmpty()) 
            {
                final ByteBuffer bb = m_toSend.peek();
                final int wrote = channel.write(bb);
                m_logger.debug("Wrote " + wrote + " bytes");
                
                if (bb.hasRemaining())
                {
                    // cannot send right now, continue
                    return;
                }
                m_toSend.remove();
            }
            
            // cleaned out the queue, no need for write notify anymore
            interestOpsClear(SelectionKey.OP_WRITE);
            
            // make sure the other side is still interested in reads
            m_otherData.interestOpsSet(SelectionKey.OP_READ);
            
            if (m_closeAfterWrite)
            {
                m_key.cancel();
                closeQuietly(channel);
            }
        }
        catch (IOException ex)
        {
            // connection probably closed out from under us
            m_logger.debug("Failed to write: ", ex);

            // not sure why channel is still marked as connected when we have a broken pipe
            // clear out send queue, since it is never going to succeed
            m_toSend.clear();
            closeBothChannels();
        }
    }
    
    /**
     * Close both channels, such as when an IO error occurs
     */
    public void closeBothChannels()
    {
        closeChannel();
        m_otherData.closeChannel();
    }
    
    private void closeChannel()
    {
        final SocketChannel channel = (SocketChannel)m_key.channel();
        if (channel.isConnected() && m_toSend.size() > 0)
        {
            // drain the rest of the data first
            m_closeAfterWrite = true;
        }
        else
        {
            m_key.cancel();
            closeQuietly(channel);
        }
    }

    static void closeQuietly(SelectableChannel channel)
    {
        try
        {
            if (channel != null)
            {
                channel.close();
            }
        }
        catch (Exception ex)
        {
            // ignore
        }
    }
}