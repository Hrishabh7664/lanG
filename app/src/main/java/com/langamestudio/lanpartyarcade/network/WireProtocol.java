package com.langamestudio.lanpartyarcade.network;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Length-prefixed, versioned LAN frame codec. All size checks happen before allocation. */
public final class WireProtocol {
    public static final int MAX_INPUT_FRAME = 4 * 1024;
    public static final int MAX_SNAPSHOT_FRAME = 64 * 1024;
    private static final int VERSION = 1;
    private static final int HEADER_SIZE = 3;

    public static final int ROOM_JOIN = 1;
    public static final int ROOM_ACCEPT = 2;
    public static final int ROOM_REJECT = 3;
    public static final int LOBBY_STATE = 4;
    public static final int READY = 5;
    public static final int START_MATCH = 6;
    public static final int INPUT = 7;
    public static final int SNAPSHOT = 8;
    public static final int SNAPSHOT_ACK = 9;
    public static final int MATCH_OVER = 10;
    public static final int LEAVE = 11;
    public static final int PING = 12;
    public static final int PONG = 13;
    public static final int REQUEST_FULL = 14;
    public static final int ROOM_CLOSED = 15;

    private WireProtocol() { }

    public static void write(DataOutputStream output, int type, byte[] payload) throws IOException {
        if (!knownType(type) || payload == null) throw new IOException("Unknown frame type or null payload");
        int length = HEADER_SIZE + payload.length;
        int limit = type == SNAPSHOT || type == START_MATCH ? MAX_SNAPSHOT_FRAME : MAX_INPUT_FRAME;
        if (length > limit) throw new IOException("Frame exceeds type limit");
        output.writeInt(length);
        output.writeShort(VERSION);
        output.writeByte(type);
        output.write(payload);
        output.flush();
    }

    public static Frame read(DataInputStream input) throws IOException {
        int length;
        try {
            length = input.readInt();
        } catch (EOFException closed) {
            throw closed;
        }
        if (length < HEADER_SIZE || length > MAX_SNAPSHOT_FRAME) throw new IOException("Frame length out of range");
        byte[] frame = new byte[length];
        input.readFully(frame);
        DataInputStream decoded = new DataInputStream(new ByteArrayInputStream(frame));
        int version = decoded.readUnsignedShort();
        int type = decoded.readUnsignedByte();
        if (version != VERSION || !knownType(type)) throw new IOException("Unsupported protocol version or message type");
        int max = type == SNAPSHOT || type == START_MATCH ? MAX_SNAPSHOT_FRAME : MAX_INPUT_FRAME;
        if (length > max) throw new IOException("Frame exceeds message type limit");
        return new Frame(type, decoded);
    }

    public static byte[] payload(PayloadWriter writer) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream output = new DataOutputStream(bytes);
        writer.write(output);
        output.flush();
        return bytes.toByteArray();
    }

    public static void writeString(DataOutputStream output, String value, int maxUtf8Bytes) throws IOException {
        if (value == null) throw new IOException("Null string");
        byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
        if (encoded.length > maxUtf8Bytes || encoded.length > 0xffff) throw new IOException("String too long");
        output.writeShort(encoded.length);
        output.write(encoded);
    }

    public static String readString(DataInputStream input, int maxUtf8Bytes) throws IOException {
        int length = input.readUnsignedShort();
        if (length > maxUtf8Bytes) throw new IOException("String too long");
        byte[] encoded = new byte[length];
        input.readFully(encoded);
        return new String(encoded, StandardCharsets.UTF_8);
    }

    public static void writeBytes(DataOutputStream output, byte[] value, int maxBytes) throws IOException {
        if (value == null || value.length > maxBytes) throw new IOException("Byte array too large");
        output.writeInt(value.length);
        output.write(value);
    }

    public static byte[] readBytes(DataInputStream input, int maxBytes) throws IOException {
        int length = input.readInt();
        if (length < 0 || length > maxBytes) throw new IOException("Byte array length out of range");
        byte[] result = new byte[length];
        input.readFully(result);
        return result;
    }

    private static boolean knownType(int type) {
        return type >= ROOM_JOIN && type <= ROOM_CLOSED;
    }

    @FunctionalInterface
    public interface PayloadWriter {
        void write(DataOutputStream output) throws IOException;
    }

    public static final class Frame {
        public final int type;
        public final DataInputStream payload;
        private Frame(int type, DataInputStream payload) { this.type = type; this.payload = payload; }
    }
}
