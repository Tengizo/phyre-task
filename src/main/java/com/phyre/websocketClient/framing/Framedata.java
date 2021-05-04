package com.phyre.websocketClient.framing;


import com.phyre.websocketClient.Opcode;

import java.nio.ByteBuffer;

/**
 * The interface for the frame
 */
public interface Framedata {

    /**
     * Indicates that this is the final fragment in a message.  The first fragment MAY also be the
     * final fragment.
     *
     * @return true, if this frame is the final fragment
     */
    boolean isFin();

    /**
     * Indicates that this frame has the rsv1 bit set.
     *
     * @return true, if this frame has the rsv1 bit set
     */
    boolean isRSV1();

    /**
     * Indicates that this frame has the rsv2 bit set.
     *
     * @return true, if this frame has the rsv2 bit set
     */
    boolean isRSV2();

    /**
     * Indicates that this frame has the rsv3 bit set.
     *
     * @return true, if this frame has the rsv3 bit set
     */
    boolean isRSV3();

    /**
     * Defines whether the "Payload data" is masked.
     *
     * @return true, "Payload data" is masked
     */
    boolean getTransfereMasked();

    /**
     * Defines the interpretation of the "Payload data".
     *
     * @return the interpretation as a Opcode
     */
    Opcode getOpcode();

    /**
     * The "Payload data" which was sent in this frame
     *
     * @return the "Payload data" as ByteBuffer
     */
    ByteBuffer getPayloadData();

}
