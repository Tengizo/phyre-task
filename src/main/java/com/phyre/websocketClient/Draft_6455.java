/*
 * Copyright (c) 2010-2020 Nathan Rajlich
 *
 *  Permission is hereby granted, free of charge, to any person
 *  obtaining a copy of this software and associated documentation
 *  files (the "Software"), to deal in the Software without
 *  restriction, including without limitation the rights to use,
 *  copy, modify, merge, publish, distribute, sublicense, and/or sell
 *  copies of the Software, and to permit persons to whom the
 *  Software is furnished to do so, subject to the following
 *  conditions:
 *
 *  The above copyright notice and this permission notice shall be
 *  included in all copies or substantial portions of the Software.
 *
 *  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 *  EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES
 *  OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 *  NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT
 *  HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
 *  WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
 *  FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR
 *  OTHER DEALINGS IN THE SOFTWARE.
 */

package com.phyre.websocketClient;

import lombok.extern.slf4j.Slf4j;
import com.phyre.websocketClient.exceptions.*;
import com.phyre.websocketClient.framing.CloseFrame;
import com.phyre.websocketClient.framing.Framedata;
import com.phyre.websocketClient.framing.FramedataImpl1;
import com.phyre.websocketClient.framing.TextFrame;
import com.phyre.websocketClient.util.Charsetfunctions;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

@Slf4j
public class Draft_6455 {

    private Framedata currentContinuousFrame;

    /**
     * Attribute for the payload of the current continuous frame
     */
    private final List<ByteBuffer> byteBufferList;

    /**
     * Attribute for the current incomplete frame
     */
    private ByteBuffer incompleteframe;

    /**
     * Attribute for the reusable random instance
     */
    private final SecureRandom reuseableRandom = new SecureRandom();

    private final int maxFrameSize = Integer.MAX_VALUE;

    public Draft_6455() {
        byteBufferList = new ArrayList<>();
    }

    public int getMaxFrameSize() {
        return maxFrameSize;
    }

    public ByteBuffer createBinaryFrame(Framedata framedata) {
        ByteBuffer mes = framedata.getPayloadData();

        int sizebytes = getSizeBytes(mes);
        ByteBuffer buf = ByteBuffer.allocate(
                1 + (sizebytes > 1 ? sizebytes + 1 : sizebytes) + 4 + mes.remaining());
        // first byte for fin, rsv1,2,3 and opcode, plus mask flag and size byte, plus potential 2 or 8 bytes for size. lastly size of message

        buf.put(createFirstByte(framedata));
        byte[] payloadLengthBytes = toByteArray(mes.remaining(), sizebytes);
        assert (payloadLengthBytes.length == sizebytes);

        if (sizebytes == 1) {
            buf.put((byte) (payloadLengthBytes[0] | getMaskByte()));
        } else if (sizebytes == 2) {
            buf.put((byte) ((byte) 126 | getMaskByte()));
            buf.put(payloadLengthBytes);
        } else if (sizebytes == 8) {
            buf.put((byte) ((byte) 127 | getMaskByte()));
            buf.put(payloadLengthBytes);
        } else {
            throw new IllegalStateException("Size representation not supported/specified");
        }
        ByteBuffer maskkey = ByteBuffer.allocate(4);
        maskkey.putInt(reuseableRandom.nextInt());
        buf.put(maskkey.array());
        for (int i = 0; mes.hasRemaining(); i++) {
            buf.put((byte) (mes.get() ^ maskkey.get(i % 4)));
        }
        assert (buf.remaining() == 0) : buf.remaining();
        buf.flip();
        return buf;
    }

    private byte createFirstByte(Framedata framedata) {
        byte optcode = fromOpcode(framedata.getOpcode());
        byte one = (byte) (framedata.isFin() ? -128 : 0);
        one |= optcode;
        if (framedata.isRSV1()) {
            one |= getRSVByte(1);
        }
        if (framedata.isRSV2()) {
            one |= getRSVByte(2);
        }
        if (framedata.isRSV3()) {
            one |= getRSVByte(3);
        }
        return one;
    }

    private Framedata translateSingleFrame(ByteBuffer buffer)
            throws IncompleteException, InvalidDataException {
        if (buffer == null) {
            throw new IllegalArgumentException();
        }
        int currentPacketSize = buffer.remaining();
        int realpacketsize = 2;
        validateResponsePacketSize(currentPacketSize, realpacketsize);
        byte b1 = buffer.get(/*0*/);
        boolean fin = b1 >> 8 != 0;
        boolean rsv1 = (b1 & 0x40) != 0;
        boolean rsv2 = (b1 & 0x20) != 0;
        boolean rsv3 = (b1 & 0x10) != 0;
        byte b2 = buffer.get(/*1*/);
        boolean mask = (b2 & -128) != 0;
        int payloadlength = (byte) (b2 & ~(byte) 128); //last 7 bits of second byte
        Opcode optcode = toOpcode((byte) (b1 & 15));
        if (mask) {
            throw new InvalidDataException(CloseFrame.PROTOCOL_ERROR, "Mask shouldn't be presented in server frame");
        }
        if (!(payloadlength <= 125)) {
            TranslatedPayloadMetaData payloadData = translateSingleFramePayloadLength(buffer,
                    payloadlength, currentPacketSize, realpacketsize);
            payloadlength = payloadData.getPayloadLength();
            realpacketsize = payloadData.getRealPackageSize();
        }
        checkFrameLengthLimit(payloadlength);
        realpacketsize += payloadlength;

        validateResponsePacketSize(currentPacketSize, realpacketsize);
        ByteBuffer payload = ByteBuffer.allocate(checkAlloc(payloadlength));

        payload.put(buffer.array(), buffer.position(), payload.limit());
        buffer.position(buffer.position() + payload.limit());
        FramedataImpl1 frame = FramedataImpl1.get(optcode);

        frame.setFin(fin);
        frame.setRSV1(rsv1);
        frame.setRSV2(rsv2);
        frame.setRSV3(rsv3);
        payload.flip();
        frame.setPayload(payload);

        frame.isValid();
        return frame;
    }


    public int checkAlloc(int bytecount) throws InvalidDataException {
        if (bytecount < 0) {
            throw new InvalidDataException(CloseFrame.PROTOCOL_ERROR, "Negative count");
        }
        return bytecount;
    }


    /**
     * Translate the buffer depending when it has an extended payload length (126 or 127)
     *
     * @param buffer            the buffer to read from
     * @param oldPayloadlength  the old payload length
     * @param maxpacketsize     the max packet size allowed
     * @param oldRealpacketsize the real packet size
     * @return the new payload data containing new payload length and new packet size
     * @throws IncompleteException    if the maxpacketsize is smaller than the realpackagesize
     * @throws LimitExceededException if the payload length is to big
     */
    private TranslatedPayloadMetaData translateSingleFramePayloadLength(ByteBuffer buffer,
                                                                        int oldPayloadlength, int maxpacketsize, int oldRealpacketsize)
            throws IncompleteException, LimitExceededException {
        int payloadlength = oldPayloadlength;
        int realpacketsize = oldRealpacketsize;
        if (payloadlength == 126) {  //If 126, the following 2 bytes interpreted as a 16-bit unsigned integer are the payload length
            realpacketsize += 2; // additional length bytes
            validateResponsePacketSize(maxpacketsize, realpacketsize);
            byte[] sizebytes = new byte[2];
            sizebytes[0] = buffer.get(/*1 + 1*/);
            sizebytes[1] = buffer.get(/*1 + 2*/);
            payloadlength = new BigInteger(sizebytes).intValue();
        } else {  //If 127, the following 8 bytes interpreted as a 64-bit unsigned integer
            realpacketsize += 8; // additional length bytes
            validateResponsePacketSize(maxpacketsize, realpacketsize);
            byte[] bytes = new byte[8];
            for (int i = 0; i < 8; i++) {
                bytes[i] = buffer.get(/*1 + i*/);
            }
            long length = new BigInteger(bytes).longValue();
            checkFrameLengthLimit(length);
            payloadlength = (int) length;
        }
        return new TranslatedPayloadMetaData(payloadlength, realpacketsize);
    }

    /**
     * Check if the frame size exceeds the allowed limit
     *
     * @param length the current payload length
     * @throws LimitExceededException if the payload length is to big
     */
    private void checkFrameLengthLimit(long length) throws LimitExceededException {
        if (length > Integer.MAX_VALUE) {
            log.trace("Limit exedeed: Payloadsize is to big...");
            throw new LimitExceededException("Payloadsize is to big...");
        }
        if (length < 0) {
            log.trace("Limit underflow: Payloadsize is to little...");
            throw new LimitExceededException("Payloadsize is to little...");
        }
    }

    /**
     * Check if the max packet size is smaller than the real packet size
     *
     * @param currentPacketSize the max packet size
     * @param realPacketSize    the real packet size
     * @throws IncompleteException if the maxpacketsize is smaller than the realpackagesize
     */
    private void validateResponsePacketSize(int currentPacketSize, int realPacketSize)
            throws IncompleteException {
        if (currentPacketSize < realPacketSize) {
            log.trace("Incomplete frame: maxpacketsize < realpacketsize");
            throw new IncompleteException(realPacketSize);
        }
    }

    /**
     * Get a byte that can set RSV bits when OR(|)'d. 0 1 2 3 4 5 6 7 +-+-+-+-+-------+ |F|R|R|R|
     * opcode| |I|S|S|S|  (4)  | |N|V|V|V|       | | |1|2|3|       |
     *
     * @param rsv Can only be {0, 1, 2, 3}
     * @return byte that represents which RSV bit is set.
     */
    private byte getRSVByte(int rsv) {
        switch (rsv) {
            case 1: // 0100 0000
                return 0x40;
            case 2: // 0010 0000
                return 0x20;
            case 3: // 0001 0000
                return 0x10;
            default:
                return 0;
        }
    }


    private byte getMaskByte() {
        return (byte) -128;
    }

    /**
     * Get the size bytes for the byte buffer
     *
     * @param mes the current buffer
     * @return the size bytes
     */
    private int getSizeBytes(ByteBuffer mes) {
        if (mes.remaining() <= 125) { //if 7 bit is enough for length
            return 1;
        } else if (mes.remaining() <= 65535) { //if 2  byte is enough for length
            return 2;
        }
        return 8;
    }

    public List<Framedata> translateFrame(ByteBuffer buffer) throws InvalidDataException {
        List<Framedata> frames = new LinkedList<>();
        Framedata cur;
        if (incompleteframe != null) {
            Framedata frame = completeFrame(buffer);
            if (frame == null) { //didn't received enough bytes to finish frame
                return Collections.emptyList();
            }
            frames.add(frame);
        }

        // Read as much as possible full frames
        while (buffer.hasRemaining()) {
            buffer.mark();
            try {
                cur = translateSingleFrame(buffer);
                frames.add(cur);
            } catch (IncompleteException e) {
                // remember the incomplete data
                buffer.reset();
                int pref = e.getPreferredSize();
                incompleteframe = ByteBuffer.allocate(checkAlloc(pref));
                incompleteframe.put(buffer);
                break;
            }
        }
        return frames;
    }

    private Framedata completeFrame(ByteBuffer buffer) throws InvalidDataException {
        Framedata frame = null;
        try {
            buffer.mark();
            int availableNextByteCount = buffer.remaining();// The number of bytes received
            int expectedNextByteCount = incompleteframe
                    .remaining();// The number of bytes to complete the incomplete frame

            if (expectedNextByteCount > availableNextByteCount) {
                // did not receive enough bytes to complete the frame
                incompleteframe.put(buffer.array(), buffer.position(), availableNextByteCount);
                buffer.position(buffer.position() + availableNextByteCount);
                return null;
            }
            incompleteframe.put(buffer.array(), buffer.position(), expectedNextByteCount);
            buffer.position(buffer.position() + expectedNextByteCount);
            frame = translateSingleFrame((ByteBuffer) incompleteframe.duplicate().position(0));
            incompleteframe = null;
        } catch (IncompleteException e) {
            // extending as much as suggested
            // case when payload length couldn't
            ByteBuffer extendedframe = ByteBuffer.allocate(checkAlloc(e.getPreferredSize()));
            assert (extendedframe.limit() > incompleteframe.limit());
            incompleteframe.rewind();
            extendedframe.put(incompleteframe);
            incompleteframe = extendedframe;
            completeFrame(buffer);
        }
        return frame;
    }


    public List<Framedata> createFrames(String text) {
        TextFrame curframe = new TextFrame();
        curframe.setPayload(ByteBuffer.wrap(Charsetfunctions.utf8Bytes(text)));
        curframe.setTransferemasked(true);
        try {
            curframe.isValid();
        } catch (InvalidDataException e) {
            throw new NotSendableException(e);
        }
        return Collections.singletonList(curframe);
    }


    private byte[] toByteArray(long val, int byteCount) {
        byte[] buffer = new byte[byteCount];
        int highest = 8 * byteCount - 8;
        for (int i = 0; i < byteCount; i++) {
            buffer[i] = (byte) (val >>> (highest - 8 * i));
        }
        return buffer;
    }


    private byte fromOpcode(Opcode opcode) {
        if (opcode == Opcode.CONTINUOUS) {
            return 0;
        } else if (opcode == Opcode.TEXT) {
            return 1;
        } else if (opcode == Opcode.BINARY) {
            return 2;
        } else if (opcode == Opcode.CLOSING) {
            return 8;
        } else if (opcode == Opcode.PING) {
            return 9;
        } else if (opcode == Opcode.PONG) {
            return 10;
        }
        throw new IllegalArgumentException("Don't know how to handle " + opcode.toString());
    }

    private Opcode toOpcode(byte opcode) throws InvalidFrameException {
        switch (opcode) {
            case 0:
                return Opcode.CONTINUOUS;
            case 1:
                return Opcode.TEXT;
            case 2:
                return Opcode.BINARY;
            // 3-7 are not yet defined
            case 8:
                return Opcode.CLOSING;
            case 9:
                return Opcode.PING;
            case 10:
                return Opcode.PONG;
            // 11-15 are not yet defined
            default:
                throw new InvalidFrameException("Unknown opcode " + (short) opcode);
        }
    }


    public void processFrame(WebsocketClient wsClient, Framedata frame)
            throws InvalidDataException {
        Opcode curop = frame.getOpcode();
        if (curop == Opcode.CLOSING) {
            processFrameClosing(wsClient, frame);
        } else if (curop == Opcode.PONG) {
            wsClient.updateLastPong();
        } else if (!frame.isFin() || curop == Opcode.CONTINUOUS) {
            processFrameContinuousAndNonFin(wsClient, frame, curop);
        } else if (currentContinuousFrame != null) {
            log.error("Protocol error: Continuous frame sequence not completed.");
            throw new InvalidDataException(CloseFrame.PROTOCOL_ERROR,
                    "Continuous frame sequence not completed.");
        } else if (curop == Opcode.TEXT) {
            processFrameText(wsClient, frame);
        } else if (curop == Opcode.BINARY) {
            processFrameBinary(wsClient, frame);
        } else {
            log.error("non control or continious frame expected");
            throw new InvalidDataException(CloseFrame.PROTOCOL_ERROR,
                    "non control or continious frame expected");
        }
    }

    /**
     * Process the frame if it is a continuous frame or the fin bit is not set
     *
     * @param webSocketImpl the websocket implementation to use
     * @param frame         the current frame
     * @param curop         the current Opcode
     * @throws InvalidDataException if there is a protocol error
     */
    private void processFrameContinuousAndNonFin(WebsocketClient webSocketImpl, Framedata frame,
                                                 Opcode curop) throws InvalidDataException {
        if (curop != Opcode.CONTINUOUS) {
            processFrameIsNotFin(frame);
        } else if (frame.isFin()) {
            processFrameIsFin(webSocketImpl, frame);
        } else if (currentContinuousFrame == null) {
            log.error("Protocol error: Continuous frame sequence was not started.");
            throw new InvalidDataException(CloseFrame.PROTOCOL_ERROR,
                    "Continuous frame sequence was not started.");
        }
        //Check if the whole payload is valid utf8, when the opcode indicates a text
        if (curop == Opcode.TEXT && !Charsetfunctions.isValidUTF8(frame.getPayloadData())) {
            log.error("Protocol error: Payload is not UTF8");
            throw new InvalidDataException(CloseFrame.NO_UTF8);
        }
        //Checking if the current continuous frame contains a correct payload with the other frames combined
        if (curop == Opcode.CONTINUOUS && currentContinuousFrame != null) {
            addToBufferList(frame.getPayloadData());
        }
    }


    private void processFrameBinary(WebsocketClient wsClient, Framedata frame) {
        try {
            wsClient.onWebsocketMessage(frame.getPayloadData());
        } catch (RuntimeException e) {
            logRuntimeException(wsClient, e);
        }
    }


    private void logRuntimeException(WebsocketClient wsClient, RuntimeException e) {
        log.error("Runtime exception during onWebsocketMessage", e);
        wsClient.onError(e);
    }

    private void processFrameText(WebsocketClient wsClient, Framedata frame) throws InvalidDataException {
        try {
            wsClient.onWebsocketMessage(Charsetfunctions.stringUtf8(frame.getPayloadData()));
        } catch (RuntimeException e) {
            logRuntimeException(wsClient, e);
        }
    }

    /**
     * Process the frame if it is the last frame
     *
     * @param wsClient the websocket impl
     * @param frame    the frame
     * @throws InvalidDataException if there is a protocol error
     */
    private void processFrameIsFin(WebsocketClient wsClient, Framedata frame)
            throws InvalidDataException {
        if (currentContinuousFrame == null) {
            log.trace("Protocol error: Previous continuous frame sequence not completed.");
            throw new InvalidDataException(CloseFrame.PROTOCOL_ERROR,
                    "Continuous frame sequence was not started.");
        }
        addToBufferList(frame.getPayloadData());
        checkBufferLimit();
        if (currentContinuousFrame.getOpcode() == Opcode.TEXT) {
            ((FramedataImpl1) currentContinuousFrame).setPayload(getPayloadFromByteBufferList());
            ((FramedataImpl1) currentContinuousFrame).isValid();
            try {
                wsClient.onWebsocketMessage(Charsetfunctions.stringUtf8(currentContinuousFrame.getPayloadData()));
            } catch (RuntimeException e) {
                logRuntimeException(wsClient, e);
            }
        } else if (currentContinuousFrame.getOpcode() == Opcode.BINARY) {
            ((FramedataImpl1) currentContinuousFrame).setPayload(getPayloadFromByteBufferList());
            ((FramedataImpl1) currentContinuousFrame).isValid();
            try {
                wsClient.onWebsocketMessage(currentContinuousFrame.getPayloadData());
            } catch (RuntimeException e) {
                logRuntimeException(wsClient, e);
            }
        }
        currentContinuousFrame = null;
        clearBufferList();
    }

    /**
     * Process the frame if it is not the last frame
     *
     * @param frame the frame
     * @throws InvalidDataException if there is a protocol error
     */
    private void processFrameIsNotFin(Framedata frame) throws InvalidDataException {
        if (currentContinuousFrame != null) {
            log.trace("Protocol error: Previous continuous frame sequence not completed.");
            throw new InvalidDataException(CloseFrame.PROTOCOL_ERROR,
                    "Previous continuous frame sequence not completed.");
        }
        currentContinuousFrame = frame;
        addToBufferList(frame.getPayloadData());
        checkBufferLimit();
    }


    private void processFrameClosing(WebsocketClient wsClient, Framedata frame) {
        int code = CloseFrame.NOCODE;
        String reason = "";
        if (frame instanceof CloseFrame) {
            CloseFrame cf = (CloseFrame) frame;
            reason = cf.getMessage();
        }
        wsClient.close(reason);
    }

    /**
     * Clear the current bytebuffer list
     */
    private void clearBufferList() {
        synchronized (byteBufferList) {
            byteBufferList.clear();
        }
    }

    /**
     * Add a payload to the current bytebuffer list
     *
     * @param payloadData the new payload
     */
    private void addToBufferList(ByteBuffer payloadData) {
        synchronized (byteBufferList) {
            byteBufferList.add(payloadData);
        }
    }

    /**
     * Check the current size of the buffer and throw an exception if the size is bigger than the max
     * allowed frame size
     *
     * @throws LimitExceededException if the current size is bigger than the allowed size
     */
    private void checkBufferLimit() throws LimitExceededException {
        long totalSize = getByteBufferListSize();
        if (totalSize > maxFrameSize) {
            clearBufferList();
            log.trace("Payload limit reached. Allowed: {} Current: {}", maxFrameSize, totalSize);
            throw new LimitExceededException(maxFrameSize);
        }
    }


    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        Draft_6455 that = (Draft_6455) o;

        return maxFrameSize == that.getMaxFrameSize();
    }

    public void reset() {
        incompleteframe = null;
    }

    /**
     * Method to generate a full bytebuffer out of all the fragmented frame payload
     *
     * @return a bytebuffer containing all the data
     * @throws LimitExceededException will be thrown when the totalSize is bigger then
     *                                Integer.MAX_VALUE due to not being able to allocate more
     */
    private ByteBuffer getPayloadFromByteBufferList() throws LimitExceededException {
        long totalSize = 0;
        ByteBuffer resultingByteBuffer;
        synchronized (byteBufferList) {
            for (ByteBuffer buffer : byteBufferList) {
                totalSize += buffer.limit();
            }
            checkBufferLimit();
            resultingByteBuffer = ByteBuffer.allocate((int) totalSize);
            for (ByteBuffer buffer : byteBufferList) {
                resultingByteBuffer.put(buffer);
            }
        }
        resultingByteBuffer.flip();
        return resultingByteBuffer;
    }

    /**
     * Get the current size of the resulting bytebuffer in the bytebuffer list
     *
     * @return the size as long (to not get an integer overflow)
     */
    private long getByteBufferListSize() {
        long totalSize = 0;
        synchronized (byteBufferList) {
            for (ByteBuffer buffer : byteBufferList) {
                totalSize += buffer.limit();
            }
        }
        return totalSize;
    }

    private static class TranslatedPayloadMetaData {

        private final int payloadLength;
        private final int realPackageSize;

        private int getPayloadLength() {
            return payloadLength;
        }

        private int getRealPackageSize() {
            return realPackageSize;
        }

        TranslatedPayloadMetaData(int newPayloadLength, int newRealPackageSize) {
            this.payloadLength = newPayloadLength;
            this.realPackageSize = newRealPackageSize;
        }
    }
}
