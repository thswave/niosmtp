/**
* Licensed to niosmtp developers ('niosmtp') under one or more
* contributor license agreements. See the NOTICE file distributed with
* this work for additional information regarding copyright ownership.
* Selene licenses this file to You under the Apache License, Version 2.0
* (the "License"); you may not use this file except in compliance with
* the License. You may obtain a copy of the License at
*
* http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/
package me.normanmaurer.niosmtp.impl.internal;

import javax.net.ssl.SSLEngine;

import me.normanmaurer.niosmtp.SMTPResponseCallback;
import me.normanmaurer.niosmtp.SMTPResponse;

import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.ChannelPipeline;
import org.jboss.netty.channel.ChannelPipelineFactory;
import org.jboss.netty.channel.Channels;
import org.jboss.netty.channel.ExceptionEvent;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.channel.SimpleChannelUpstreamHandler;
import org.jboss.netty.handler.codec.frame.DelimiterBasedFrameDecoder;
import org.jboss.netty.handler.codec.frame.Delimiters;
import org.jboss.netty.handler.stream.ChunkedWriteHandler;
import org.jboss.netty.handler.timeout.IdleStateHandler;
import org.jboss.netty.util.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link ChannelPipelineFactory} which is used for the SMTP Client
 * 
 * @author Norman Maurer
 * 
 *
 */
public class SMTPClientPipelineFactory implements ChannelPipelineFactory{
    private final static Logger LOGGER = LoggerFactory.getLogger(SMTPClientPipelineFactory.class);
    private final static DelimiterBasedFrameDecoder FRAMER = new DelimiterBasedFrameDecoder(8192, true, Delimiters.lineDelimiter());
    private final static SMTPResponseDecoder SMTP_RESPONSE_DECODER = new SMTPResponseDecoder();
    private final static SMTPRequestEncoder SMTP_REQUEST_ENCODER = new SMTPRequestEncoder();
    private final static SMTPClientIdleHandler SMTP_CLIENT_IDLE_HANDLER = new SMTPClientIdleHandler();
    private Timer timer;
    private int responseTime;
    private SMTPResponseCallback callback;
    
    public SMTPClientPipelineFactory(SMTPResponseCallback callback, Timer timer, int responseTimeout) {
        this.timer = timer;
        this.responseTime = responseTimeout;
        this.callback = callback;
    }
    
    
    @Override
    public ChannelPipeline getPipeline() throws Exception {
        ChannelPipeline pipeline = Channels.pipeline();
        pipeline.addLast("smtpIdleHandler", SMTP_CLIENT_IDLE_HANDLER);
        pipeline.addLast("framer", FRAMER);
        pipeline.addLast("decoder", SMTP_RESPONSE_DECODER);
        pipeline.addLast("encoder", SMTP_REQUEST_ENCODER);
        pipeline.addLast("chunk", new ChunkedWriteHandler());
        
        // Add the idle timeout handler
        pipeline.addLast("idleHandler", new IdleStateHandler(timer, 0, 0, responseTime));
        pipeline.addLast("connectHandler", createConnectHandler());
        return pipeline;
    }
    
    protected ConnectHandler createConnectHandler() {
        return new ConnectHandler(false, null);
    }
    
    public class ConnectHandler extends SimpleChannelUpstreamHandler {

        private SSLEngine engine;
        private boolean startTLS;

        ConnectHandler(boolean startTLS, SSLEngine engine){
            this.engine = engine;
            this.startTLS = startTLS;
        }
        
        
        @Override
        public void messageReceived(ChannelHandlerContext ctx, MessageEvent e) throws Exception {
            Object msg = e.getMessage();
            if (msg instanceof SMTPResponse) {
                callback.onResponse(new NettySMTPClientSession(ctx.getChannel(), LOGGER, startTLS, engine), (SMTPResponse) msg);
                ctx.getChannel().getPipeline().remove(this);
            } else {
                super.messageReceived(ctx, e);
            }
        }
        
        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, ExceptionEvent e) throws Exception {
            callback.onException(new NettySMTPClientSession(ctx.getChannel(), LOGGER, startTLS, engine), e.getCause());
            ctx.getChannel().getPipeline().remove(this);

        }
    }

}
