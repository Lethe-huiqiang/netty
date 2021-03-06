/*
 * Copyright 2014 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License, version 2.0 (the
 * "License"); you may not use this file except in compliance with the License. You may obtain a
 * copy of the License at:
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package io.netty.handler.codec.http2;

import static io.netty.buffer.Unpooled.EMPTY_BUFFER;
import static io.netty.buffer.Unpooled.copiedBuffer;
import static io.netty.buffer.Unpooled.wrappedBuffer;
import static io.netty.handler.codec.http2.Http2CodecUtil.DEFAULT_MAX_FRAME_SIZE;
import static io.netty.handler.codec.http2.Http2CodecUtil.DEFAULT_PRIORITY_WEIGHT;
import static io.netty.handler.codec.http2.Http2CodecUtil.connectionPrefaceBuf;
import static io.netty.handler.codec.http2.Http2CodecUtil.emptyPingBuf;
import static io.netty.handler.codec.http2.Http2Error.INTERNAL_ERROR;
import static io.netty.handler.codec.http2.Http2Error.NO_ERROR;
import static io.netty.handler.codec.http2.Http2Error.PROTOCOL_ERROR;
import static io.netty.handler.codec.http2.Http2Exception.protocolError;
import static io.netty.handler.codec.http2.Http2Headers.EMPTY_HEADERS;
import static io.netty.handler.codec.http2.Http2Stream.State.HALF_CLOSED_LOCAL;
import static io.netty.handler.codec.http2.Http2Stream.State.OPEN;
import static io.netty.handler.codec.http2.Http2Stream.State.RESERVED_LOCAL;
import static io.netty.handler.codec.http2.Http2Stream.State.RESERVED_REMOTE;
import static io.netty.util.CharsetUtil.UTF_8;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyBoolean;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.anyLong;
import static org.mockito.Matchers.anyShort;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.buffer.UnpooledByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.channel.DefaultChannelPromise;

import java.util.Collections;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Tests for {@link DelegatingHttp2ConnectionHandlerTest} and its base class {@link AbstractHttp2ConnectionHandler}.
 */
public class DelegatingHttp2ConnectionHandlerTest {
    private static final int STREAM_ID = 1;
    private static final int PUSH_STREAM_ID = 2;

    private DelegatingHttp2ConnectionHandler handler;

    @Mock
    private Http2Connection connection;

    @Mock
    private Http2Connection.Endpoint remote;

    @Mock
    private Http2Connection.Endpoint local;

    @Mock
    private Http2InboundFlowController inboundFlow;

    @Mock
    private Http2OutboundFlowController outboundFlow;

    @Mock
    private ChannelHandlerContext ctx;

    @Mock
    private Channel channel;

    private ChannelPromise promise;

    @Mock
    private ChannelFuture future;

    @Mock
    private Http2Stream stream;

    @Mock
    private Http2Stream pushStream;

    @Mock
    private Http2FrameListener listener;

    @Mock
    private Http2FrameReader reader;

    @Mock
    private Http2FrameWriter writer;

    @Before
    public void setup() throws Exception {
        MockitoAnnotations.initMocks(this);

        promise = new DefaultChannelPromise(channel);

        when(channel.isActive()).thenReturn(true);
        when(stream.id()).thenReturn(STREAM_ID);
        when(stream.state()).thenReturn(OPEN);
        when(pushStream.id()).thenReturn(PUSH_STREAM_ID);
        when(connection.activeStreams()).thenReturn(Collections.singletonList(stream));
        when(connection.stream(STREAM_ID)).thenReturn(stream);
        when(connection.requireStream(STREAM_ID)).thenReturn(stream);
        when(connection.local()).thenReturn(local);
        when(connection.remote()).thenReturn(remote);
        when(local.createStream(eq(STREAM_ID), anyBoolean())).thenReturn(stream);
        when(local.reservePushStream(eq(PUSH_STREAM_ID), eq(stream))).thenReturn(pushStream);
        when(remote.createStream(eq(STREAM_ID), anyBoolean())).thenReturn(stream);
        when(remote.reservePushStream(eq(PUSH_STREAM_ID), eq(stream))).thenReturn(pushStream);
        when(writer.writeSettings(eq(ctx), any(Http2Settings.class), eq(promise))).thenReturn(future);
        when(writer.writeGoAway(eq(ctx), anyInt(), anyInt(), any(ByteBuf.class), eq(promise))).thenReturn(future);
        when(outboundFlow.writeData(eq(ctx), anyInt(), any(ByteBuf.class), anyInt(), anyBoolean(), eq(promise)))
                .thenReturn(future);
        mockContext();

        handler = new DelegatingHttp2ConnectionHandler(connection, reader, writer, inboundFlow, outboundFlow, listener);

        // Simulate activation of the handler to force writing the initial settings.
        Http2Settings settings = new Http2Settings();
        settings.initialWindowSize(10);
        settings.pushEnabled(true);
        settings.maxConcurrentStreams(100);
        settings.headerTableSize(200);
        settings.maxFrameSize(DEFAULT_MAX_FRAME_SIZE);
        settings.maxHeaderListSize(Integer.MAX_VALUE);
        when(inboundFlow.initialInboundWindowSize()).thenReturn(10);
        when(local.allowPushTo()).thenReturn(true);
        when(remote.maxStreams()).thenReturn(100);
        when(reader.maxHeaderTableSize()).thenReturn(200L);
        when(reader.maxFrameSize()).thenReturn(DEFAULT_MAX_FRAME_SIZE);
        when(writer.maxFrameSize()).thenReturn(DEFAULT_MAX_FRAME_SIZE);
        when(reader.maxHeaderListSize()).thenReturn(Integer.MAX_VALUE);
        when(writer.maxHeaderListSize()).thenReturn(Integer.MAX_VALUE);
        handler.handlerAdded(ctx);
        verify(writer).writeSettings(eq(ctx), eq(settings), eq(promise));

        // Simulate receiving the initial settings from the remote endpoint.
        decode().onSettingsRead(ctx, new Http2Settings());
        verify(listener).onSettingsRead(eq(ctx), eq(new Http2Settings()));
        verify(writer).writeSettingsAck(eq(ctx), eq(promise));

        // Simulate receiving the SETTINGS ACK for the initial settings.
        decode().onSettingsAckRead(ctx);

        // Re-mock the context so no calls are registered.
        mockContext();
        handler.handlerAdded(ctx);
    }

    @After
    public void tearDown() throws Exception {
        handler.handlerRemoved(ctx);
    }

    @Test
    public void clientShouldSendClientPrefaceStringWhenActive() throws Exception {
        when(connection.isServer()).thenReturn(false);
        handler = new DelegatingHttp2ConnectionHandler(connection, reader, writer, inboundFlow, outboundFlow, listener);
        handler.channelActive(ctx);
        verify(ctx).write(eq(connectionPrefaceBuf()));
    }

    @Test
    public void serverShouldNotSendClientPrefaceStringWhenActive() throws Exception {
        when(connection.isServer()).thenReturn(true);
        handler = new DelegatingHttp2ConnectionHandler(connection, reader, writer, inboundFlow, outboundFlow, listener);
        handler.channelActive(ctx);
        verify(ctx, never()).write(eq(connectionPrefaceBuf()));
    }

    @Test
    public void serverReceivingInvalidClientPrefaceStringShouldCloseConnection() throws Exception {
        when(connection.isServer()).thenReturn(true);
        handler = new DelegatingHttp2ConnectionHandler(connection, reader, writer, inboundFlow, outboundFlow, listener);
        handler.channelRead(ctx, copiedBuffer("BAD_PREFACE", UTF_8));
        verify(ctx).close();
    }

    @Test
    public void serverReceivingValidClientPrefaceStringShouldContinueReadingFrames() throws Exception {
        reset(listener);
        when(connection.isServer()).thenReturn(true);
        handler = new DelegatingHttp2ConnectionHandler(connection, reader, writer, inboundFlow, outboundFlow, listener);
        handler.channelRead(ctx, connectionPrefaceBuf());
        verify(ctx, never()).close();
        decode().onSettingsRead(ctx, new Http2Settings());
        verify(listener).onSettingsRead(eq(ctx), eq(new Http2Settings()));
    }

    @Test
    public void closeShouldSendGoAway() throws Exception {
        handler.close(ctx, promise);
        verify(writer).writeGoAway(eq(ctx), eq(0), eq((long) NO_ERROR.code()), eq(EMPTY_BUFFER), eq(promise));
        verify(remote).goAwayReceived(0);
    }

    @Test
    public void channelInactiveShouldCloseStreams() throws Exception {
        handler.channelInactive(ctx);
        verify(stream).close();
    }

    @Test
    public void streamErrorShouldCloseStream() throws Exception {
        Http2Exception e = new Http2StreamException(STREAM_ID, PROTOCOL_ERROR);
        handler.exceptionCaught(ctx, e);
        verify(stream).close();
        verify(writer).writeRstStream(eq(ctx), eq(STREAM_ID), eq((long) PROTOCOL_ERROR.code()), eq(promise));
    }

    @Test
    public void connectionErrorShouldSendGoAway() throws Exception {
        Http2Exception e = new Http2Exception(PROTOCOL_ERROR);
        when(remote.lastStreamCreated()).thenReturn(STREAM_ID);
        handler.exceptionCaught(ctx, e);
        verify(remote).goAwayReceived(STREAM_ID);
        verify(writer).writeGoAway(eq(ctx), eq(STREAM_ID), eq((long) PROTOCOL_ERROR.code()), eq(EMPTY_BUFFER),
                eq(promise));
    }

    @Test
    public void dataReadAfterGoAwayShouldApplyFlowControl() throws Exception {
        when(remote.isGoAwayReceived()).thenReturn(true);
        final ByteBuf data = dummyData();
        try {
            decode().onDataRead(ctx, STREAM_ID, data, 10, true);
            verify(inboundFlow).onDataRead(eq(ctx), eq(STREAM_ID), eq(data), eq(10), eq(true));

            // Verify that the event was absorbed and not propagated to the oberver.
            verify(listener, never()).onDataRead(eq(ctx), anyInt(), any(ByteBuf.class), anyInt(), anyBoolean());
        } finally {
            data.release();
        }
    }

    @Test
    public void dataReadWithEndOfStreamShouldCloseRemoteSide() throws Exception {
        final ByteBuf data = dummyData();
        try {
            decode().onDataRead(ctx, STREAM_ID, data, 10, true);
            verify(inboundFlow).onDataRead(eq(ctx), eq(STREAM_ID), eq(data), eq(10), eq(true));
            verify(stream).closeRemoteSide();
            verify(listener).onDataRead(eq(ctx), eq(STREAM_ID), eq(data), eq(10), eq(true));
        } finally {
            data.release();
        }
    }

    @Test
    public void headersReadAfterGoAwayShouldBeIgnored() throws Exception {
        when(remote.isGoAwayReceived()).thenReturn(true);
        decode().onHeadersRead(ctx, STREAM_ID, EMPTY_HEADERS, 0, false);
        verify(remote, never()).createStream(eq(STREAM_ID), eq(false));

        // Verify that the event was absorbed and not propagated to the oberver.
        verify(listener, never()).onHeadersRead(eq(ctx), anyInt(), any(Http2Headers.class), anyInt(), anyBoolean());
        verify(remote, never()).createStream(anyInt(), anyBoolean());
    }

    @Test
    public void headersReadForUnknownStreamShouldCreateStream() throws Exception {
        when(remote.createStream(eq(5), eq(false))).thenReturn(stream);
        decode().onHeadersRead(ctx, 5, EMPTY_HEADERS, 0, false);
        verify(remote).createStream(eq(5), eq(false));
        verify(listener).onHeadersRead(eq(ctx), eq(5), eq(EMPTY_HEADERS), eq(0), eq(DEFAULT_PRIORITY_WEIGHT),
                eq(false), eq(0), eq(false));
    }

    @Test
    public void headersReadForUnknownStreamShouldCreateHalfClosedStream() throws Exception {
        when(remote.createStream(eq(5), eq(true))).thenReturn(stream);
        decode().onHeadersRead(ctx, 5, EMPTY_HEADERS, 0, true);
        verify(remote).createStream(eq(5), eq(true));
        verify(listener).onHeadersRead(eq(ctx), eq(5), eq(EMPTY_HEADERS), eq(0), eq(DEFAULT_PRIORITY_WEIGHT),
                eq(false), eq(0), eq(true));
    }

    @Test
    public void headersReadForPromisedStreamShouldHalfOpenStream() throws Exception {
        when(stream.state()).thenReturn(RESERVED_REMOTE);
        decode().onHeadersRead(ctx, STREAM_ID, EMPTY_HEADERS, 0, false);
        verify(stream).openForPush();
        verify(listener).onHeadersRead(eq(ctx), eq(STREAM_ID), eq(EMPTY_HEADERS), eq(0), eq(DEFAULT_PRIORITY_WEIGHT),
                eq(false), eq(0), eq(false));
    }

    @Test
    public void headersReadForPromisedStreamShouldCloseStream() throws Exception {
        when(stream.state()).thenReturn(RESERVED_REMOTE);
        decode().onHeadersRead(ctx, STREAM_ID, EMPTY_HEADERS, 0, true);
        verify(stream).openForPush();
        verify(stream).close();
        verify(listener).onHeadersRead(eq(ctx), eq(STREAM_ID), eq(EMPTY_HEADERS), eq(0), eq(DEFAULT_PRIORITY_WEIGHT),
                eq(false), eq(0), eq(true));
    }

    @Test
    public void pushPromiseReadAfterGoAwayShouldBeIgnored() throws Exception {
        when(remote.isGoAwayReceived()).thenReturn(true);
        decode().onPushPromiseRead(ctx, STREAM_ID, PUSH_STREAM_ID, EMPTY_HEADERS, 0);
        verify(remote, never()).reservePushStream(anyInt(), any(Http2Stream.class));
        verify(listener, never()).onPushPromiseRead(eq(ctx), anyInt(), anyInt(), any(Http2Headers.class), anyInt());
    }

    @Test
    public void pushPromiseReadShouldSucceed() throws Exception {
        decode().onPushPromiseRead(ctx, STREAM_ID, PUSH_STREAM_ID, EMPTY_HEADERS, 0);
        verify(remote).reservePushStream(eq(PUSH_STREAM_ID), eq(stream));
        verify(listener).onPushPromiseRead(eq(ctx), eq(STREAM_ID), eq(PUSH_STREAM_ID), eq(EMPTY_HEADERS), eq(0));
    }

    @Test
    public void priorityReadAfterGoAwayShouldBeIgnored() throws Exception {
        when(remote.isGoAwayReceived()).thenReturn(true);
        decode().onPriorityRead(ctx, STREAM_ID, 0, (short) 255, true);
        verify(stream, never()).setPriority(anyInt(), anyShort(), anyBoolean());
        verify(listener, never()).onPriorityRead(eq(ctx), anyInt(), anyInt(), anyShort(), anyBoolean());
    }

    @Test
    public void priorityReadShouldSucceed() throws Exception {
        decode().onPriorityRead(ctx, STREAM_ID, 0, (short) 255, true);
        verify(stream).setPriority(eq(0), eq((short) 255), eq(true));
        verify(listener).onPriorityRead(eq(ctx), eq(STREAM_ID), eq(0), eq((short) 255), eq(true));
    }

    @Test
    public void windowUpdateReadAfterGoAwayShouldBeIgnored() throws Exception {
        when(remote.isGoAwayReceived()).thenReturn(true);
        decode().onWindowUpdateRead(ctx, STREAM_ID, 10);
        verify(outboundFlow, never()).updateOutboundWindowSize(anyInt(), anyInt());
        verify(listener, never()).onWindowUpdateRead(eq(ctx), anyInt(), anyInt());
    }

    @Test(expected = Http2Exception.class)
    public void windowUpdateReadForUnknownStreamShouldThrow() throws Exception {
        when(connection.requireStream(5)).thenThrow(protocolError(""));
        decode().onWindowUpdateRead(ctx, 5, 10);
    }

    @Test
    public void windowUpdateReadShouldSucceed() throws Exception {
        decode().onWindowUpdateRead(ctx, STREAM_ID, 10);
        verify(outboundFlow).updateOutboundWindowSize(eq(STREAM_ID), eq(10));
        verify(listener).onWindowUpdateRead(eq(ctx), eq(STREAM_ID), eq(10));
    }

    @Test
    public void rstStreamReadAfterGoAwayShouldSucceed() throws Exception {
        when(remote.isGoAwayReceived()).thenReturn(true);
        decode().onRstStreamRead(ctx, STREAM_ID, PROTOCOL_ERROR.code());
        verify(stream).close();
        verify(listener).onRstStreamRead(eq(ctx), anyInt(), anyLong());
    }

    @Test(expected = Http2Exception.class)
    public void rstStreamReadForUnknownStreamShouldThrow() throws Exception {
        when(connection.requireStream(5)).thenThrow(protocolError(""));
        decode().onRstStreamRead(ctx, 5, PROTOCOL_ERROR.code());
    }

    @Test
    public void rstStreamReadShouldCloseStream() throws Exception {
        decode().onRstStreamRead(ctx, STREAM_ID, PROTOCOL_ERROR.code());
        verify(stream).close();
        verify(listener).onRstStreamRead(eq(ctx), eq(STREAM_ID), eq((long) PROTOCOL_ERROR.code()));
    }

    @Test
    public void pingReadWithAckShouldNotifylistener() throws Exception {
        decode().onPingAckRead(ctx, emptyPingBuf());
        verify(listener).onPingAckRead(eq(ctx), eq(emptyPingBuf()));
    }

    @Test
    public void pingReadShouldReplyWithAck() throws Exception {
        decode().onPingRead(ctx, emptyPingBuf());
        verify(writer).writePing(eq(ctx), eq(true), eq(emptyPingBuf()), eq(promise));
        verify(listener, never()).onPingAckRead(eq(ctx), any(ByteBuf.class));
    }

    @Test
    public void settingsReadWithAckShouldNotifylistener() throws Exception {
        decode().onSettingsAckRead(ctx);
        // Take into account the time this was called during setup().
        verify(listener, times(2)).onSettingsAckRead(eq(ctx));
    }

    @Test(expected = Http2Exception.class)
    public void clientSettingsReadWithPushShouldThrow() throws Exception {
        when(connection.isServer()).thenReturn(false);
        Http2Settings settings = new Http2Settings();
        settings.pushEnabled(true);
        decode().onSettingsRead(ctx, settings);
    }

    @Test
    public void settingsReadShouldSetValues() throws Exception {
        when(connection.isServer()).thenReturn(true);
        Http2Settings settings = new Http2Settings();
        settings.pushEnabled(true);
        settings.initialWindowSize(123);
        settings.maxConcurrentStreams(456);
        settings.headerTableSize(789);
        decode().onSettingsRead(ctx, settings);
        verify(remote).allowPushTo(true);
        verify(outboundFlow).initialOutboundWindowSize(123);
        verify(local).maxStreams(456);
        verify(writer).maxHeaderTableSize(789L);
        // Take into account the time this was called during setup().
        verify(writer, times(2)).writeSettingsAck(eq(ctx), eq(promise));
        verify(listener).onSettingsRead(eq(ctx), eq(settings));
    }

    @Test
    public void goAwayShouldReadShouldUpdateConnectionState() throws Exception {
        decode().onGoAwayRead(ctx, 1, 2L, EMPTY_BUFFER);
        verify(local).goAwayReceived(1);
        verify(listener).onGoAwayRead(eq(ctx), eq(1), eq(2L), eq(EMPTY_BUFFER));
    }

    @Test
    public void dataWriteAfterGoAwayShouldFail() throws Exception {
        when(connection.isGoAway()).thenReturn(true);
        final ByteBuf data = dummyData();
        try {
            ChannelFuture future = handler.writeData(ctx, STREAM_ID, data, 0, false, promise);
            assertTrue(future.awaitUninterruptibly().cause() instanceof Http2Exception);
        } finally {
            while (data.refCnt() > 0) {
                data.release();
            }
        }
    }

    @Test
    public void dataWriteShouldSucceed() throws Exception {
        final ByteBuf data = dummyData();
        try {
            handler.writeData(ctx, STREAM_ID, data, 0, false, promise);
            verify(outboundFlow).writeData(eq(ctx), eq(STREAM_ID), eq(data), eq(0), eq(false), eq(promise));
        } finally {
            data.release();
        }
    }

    @Test
    public void dataWriteShouldHalfCloseStream() throws Exception {
        reset(future);
        final ByteBuf data = dummyData();
        try {
            handler.writeData(ctx, STREAM_ID, data, 0, true, promise);
            verify(outboundFlow).writeData(eq(ctx), eq(STREAM_ID), eq(data), eq(0), eq(true), eq(promise));

            // Invoke the listener callback indicating that the write completed successfully.
            ArgumentCaptor<ChannelFutureListener> captor = ArgumentCaptor.forClass(ChannelFutureListener.class);
            verify(future).addListener(captor.capture());
            when(future.isSuccess()).thenReturn(true);
            captor.getValue().operationComplete(future);
            verify(stream).closeLocalSide();
        } finally {
            data.release();
        }
    }

    @Test
    public void dataWriteWithFailureShouldHandleException() throws Exception {
        reset(future);
        final String msg = "fake exception";
        final ByteBuf exceptionData = Unpooled.copiedBuffer(msg.getBytes(UTF_8));
        final ByteBuf data = dummyData();
        List<ByteBuf> goAwayDataCapture = null;
        try {
            handler.writeData(ctx, STREAM_ID, data, 0, true, promise);
            verify(outboundFlow).writeData(eq(ctx), eq(STREAM_ID), eq(data), eq(0), eq(true), eq(promise));

            // Invoke the listener callback indicating that the write failed.
            ArgumentCaptor<ChannelFutureListener> captor = ArgumentCaptor.forClass(ChannelFutureListener.class);
            verify(future).addListener(captor.capture());
            when(future.isSuccess()).thenReturn(false);
            when(future.cause()).thenReturn(new RuntimeException(msg));
            captor.getValue().operationComplete(future);
            final ArgumentCaptor<ByteBuf> bufferCaptor = ArgumentCaptor.forClass(ByteBuf.class);
            verify(writer).writeGoAway(eq(ctx), eq(0), eq((long) INTERNAL_ERROR.code()), bufferCaptor.capture(),
                    eq(promise));
            goAwayDataCapture = bufferCaptor.getAllValues();
            assertEquals(exceptionData, goAwayDataCapture.get(0));
            verify(remote).goAwayReceived(0);
        } finally {
            data.release();
            exceptionData.release();
            if (goAwayDataCapture != null) {
                for (int i = 0; i < goAwayDataCapture.size(); ++i) {
                    goAwayDataCapture.get(i).release();
                }
            }
        }
    }

    @Test
    public void headersWriteAfterGoAwayShouldFail() throws Exception {
        when(connection.isGoAway()).thenReturn(true);
        ChannelFuture future = handler.writeHeaders(ctx, 5, EMPTY_HEADERS, 0, (short) 255, false, 0, false, promise);
        verify(local, never()).createStream(anyInt(), anyBoolean());
        verify(writer, never()).writeHeaders(eq(ctx), anyInt(), any(Http2Headers.class), anyInt(), anyBoolean(),
                eq(promise));
        assertTrue(future.awaitUninterruptibly().cause() instanceof Http2Exception);
    }

    @Test
    public void headersWriteForUnknownStreamShouldCreateStream() throws Exception {
        when(local.createStream(eq(5), eq(false))).thenReturn(stream);
        handler.writeHeaders(ctx, 5, EMPTY_HEADERS, 0, false, promise);
        verify(local).createStream(eq(5), eq(false));
        verify(writer).writeHeaders(eq(ctx), eq(5), eq(EMPTY_HEADERS), eq(0), eq(DEFAULT_PRIORITY_WEIGHT), eq(false),
                eq(0), eq(false), eq(promise));
    }

    @Test
    public void headersWriteShouldCreateHalfClosedStream() throws Exception {
        when(local.createStream(eq(5), eq(true))).thenReturn(stream);
        handler.writeHeaders(ctx, 5, EMPTY_HEADERS, 0, true, promise);
        verify(local).createStream(eq(5), eq(true));
        verify(writer).writeHeaders(eq(ctx), eq(5), eq(EMPTY_HEADERS), eq(0), eq(DEFAULT_PRIORITY_WEIGHT), eq(false),
                eq(0), eq(true), eq(promise));
    }

    @Test
    public void headersWriteShouldOpenStreamForPush() throws Exception {
        when(stream.state()).thenReturn(RESERVED_LOCAL);
        handler.writeHeaders(ctx, STREAM_ID, EMPTY_HEADERS, 0, false, promise);
        verify(stream).openForPush();
        verify(stream, never()).closeLocalSide();
        verify(writer).writeHeaders(eq(ctx), eq(STREAM_ID), eq(EMPTY_HEADERS), eq(0), eq(DEFAULT_PRIORITY_WEIGHT),
                eq(false), eq(0), eq(false), eq(promise));
    }

    @Test
    public void headersWriteShouldClosePushStream() throws Exception {
        when(stream.state()).thenReturn(RESERVED_LOCAL).thenReturn(HALF_CLOSED_LOCAL);
        handler.writeHeaders(ctx, STREAM_ID, EMPTY_HEADERS, 0, true, promise);
        verify(stream).openForPush();
        verify(stream).closeLocalSide();
        verify(writer).writeHeaders(eq(ctx), eq(STREAM_ID), eq(EMPTY_HEADERS), eq(0), eq(DEFAULT_PRIORITY_WEIGHT),
                eq(false), eq(0), eq(true), eq(promise));
    }

    @Test
    public void pushPromiseWriteAfterGoAwayShouldFail() throws Exception {
        when(connection.isGoAway()).thenReturn(true);
        ChannelFuture future = handler.writePushPromise(ctx, STREAM_ID, PUSH_STREAM_ID, EMPTY_HEADERS, 0, promise);
        assertTrue(future.awaitUninterruptibly().cause() instanceof Http2Exception);
    }

    @Test
    public void pushPromiseWriteShouldReserveStream() throws Exception {
        handler.writePushPromise(ctx, STREAM_ID, PUSH_STREAM_ID, EMPTY_HEADERS, 0, promise);
        verify(local).reservePushStream(eq(PUSH_STREAM_ID), eq(stream));
        verify(writer).writePushPromise(eq(ctx), eq(STREAM_ID), eq(PUSH_STREAM_ID), eq(EMPTY_HEADERS), eq(0),
                eq(promise));
    }

    @Test
    public void priorityWriteAfterGoAwayShouldFail() throws Exception {
        when(connection.isGoAway()).thenReturn(true);
        ChannelFuture future = handler.writePriority(ctx, STREAM_ID, 0, (short) 255, true, promise);
        assertTrue(future.awaitUninterruptibly().cause() instanceof Http2Exception);
    }

    @Test
    public void priorityWriteShouldSetPriorityForStream() throws Exception {
        handler.writePriority(ctx, STREAM_ID, 0, (short) 255, true, promise);
        verify(stream).setPriority(eq(0), eq((short) 255), eq(true));
        verify(writer).writePriority(eq(ctx), eq(STREAM_ID), eq(0), eq((short) 255), eq(true), eq(promise));
    }

    @Test
    public void rstStreamWriteForUnknownStreamShouldIgnore() throws Exception {
        handler.writeRstStream(ctx, 5, PROTOCOL_ERROR.code(), promise);
        verify(writer, never()).writeRstStream(eq(ctx), anyInt(), anyLong(), eq(promise));
    }

    @Test
    public void rstStreamWriteShouldCloseStream() throws Exception {
        handler.writeRstStream(ctx, STREAM_ID, PROTOCOL_ERROR.code(), promise);
        verify(stream).close();
        verify(writer).writeRstStream(eq(ctx), eq(STREAM_ID), eq((long) PROTOCOL_ERROR.code()), eq(promise));
    }

    @Test
    public void pingWriteAfterGoAwayShouldFail() throws Exception {
        when(connection.isGoAway()).thenReturn(true);
        ChannelFuture future = handler.writePing(ctx, emptyPingBuf(), promise);
        assertTrue(future.awaitUninterruptibly().cause() instanceof Http2Exception);
    }

    @Test
    public void pingWriteShouldSucceed() throws Exception {
        handler.writePing(ctx, emptyPingBuf(), promise);
        verify(writer).writePing(eq(ctx), eq(false), eq(emptyPingBuf()), eq(promise));
    }

    @Test
    public void settingsWriteAfterGoAwayShouldFail() throws Exception {
        when(connection.isGoAway()).thenReturn(true);
        ChannelFuture future = handler.writeSettings(ctx, new Http2Settings(), promise);
        assertTrue(future.awaitUninterruptibly().cause() instanceof Http2Exception);
    }

    @Test
    public void settingsWriteShouldNotUpdateSettings() throws Exception {
        Http2Settings settings = new Http2Settings();
        settings.initialWindowSize(100);
        settings.pushEnabled(false);
        settings.maxConcurrentStreams(1000);
        settings.headerTableSize(2000);
        handler.writeSettings(ctx, settings, promise);
        verify(writer).writeSettings(eq(ctx), eq(settings), eq(promise));
        // Verify that application of local settings must not be done when it is dispatched.
        verify(inboundFlow, never()).initialInboundWindowSize(eq(100));
        verify(local, never()).allowPushTo(eq(false));
        verify(remote, never()).maxStreams(eq(1000));
        verify(reader, never()).maxHeaderTableSize(eq(2000L));
        // Verify that settings values are applied on the reception of SETTINGS ACK
        decode().onSettingsAckRead(ctx);
        verify(inboundFlow).initialInboundWindowSize(eq(100));
        verify(local).allowPushTo(eq(false));
        verify(remote).maxStreams(eq(1000));
        verify(reader).maxHeaderTableSize(eq(2000L));
    }

    private static ByteBuf dummyData() {
        // The buffer is purposely 8 bytes so it will even work for a ping frame.
        return wrappedBuffer("abcdefgh".getBytes(UTF_8));
    }

    private void mockContext() {
        reset(ctx);
        when(ctx.alloc()).thenReturn(UnpooledByteBufAllocator.DEFAULT);
        when(ctx.channel()).thenReturn(channel);
        when(ctx.newSucceededFuture()).thenReturn(future);
        when(ctx.newPromise()).thenReturn(promise);
        when(ctx.write(any())).thenReturn(future);
    }

    /**
     * Calls the decode method on the handler and gets back the captured internal listener
     */
    private Http2FrameListener decode() throws Exception {
        ArgumentCaptor<Http2FrameListener> internallistener = ArgumentCaptor.forClass(Http2FrameListener.class);
        doNothing().when(reader).readFrame(eq(ctx), any(ByteBuf.class), internallistener.capture());
        handler.decode(ctx, EMPTY_BUFFER, Collections.emptyList());
        return internallistener.getValue();
    }
}
