package edu.nyu.dcn.bittorrentudp.core;

import java.util.Arrays;

/**
 * Created by Ramandeep Singh on 16-12-2015.
 */
public class Packet {

    public static final int MAGIC_NUMBER=15441;
    public static final short VERSION_NUMBER=1;
    public static final int HEADER_LENGTH=24;
    public static final int CHUNK_HASH_SIZE=40;
    public static final int MAX_PACKET_SIZE=1500;
    public  enum PACKETTYPE {
        WHOHAS ,
        IHAVE  ,
        GET   ,
        DATA   ,
        ACK   ,
        DENIED ,
    };
    //4 bytes as java short ranges has range only till -2^15 to 2^15-1
    //This should be 15441 checked by peers
    int magic;
    //2 bytes instead of 1 byte as byte has range only till 127
    //this should be 1
    short version;
    //2 bytes instead of 1 byte as byte has range only till 127
    short type;
    //4 bytes as java short ranges has range only till -2^15 to 2^15-1
    int headerLength;
    //4 bytes as java short ranges has range only till -2^15 to 2^15-1
    int packetLength;
    // Java int can now store till 2^32-1 so no need to upgrade its type
    int seq;
    // Java int can now store till 2^32-1 so no need to upgrade its type
    int ack;
    // Rest of the payload.
    byte payload[];

    public Packet(int magic, short version, short type, int headerLength, int packetLength, int seq, int ack, byte[] payload) {
        this.magic = magic;
        this.version = version;
        this.type = type;
        this.headerLength = headerLength;
        this.packetLength = packetLength;
        this.seq = seq;
        this.ack = ack;
        this.payload = payload;
    }

    public int getMagic() {
        return magic;
    }

    public void setMagic(int magic) {
        this.magic = magic;
    }

    public short getVersion() {
        return version;
    }

    public void setVersion(short version) {
        this.version = version;
    }

    public short getType() {
        return type;
    }

    public void setType(short type) {
        this.type = type;
    }

    public int getHeaderLength() {
        return headerLength;
    }

    public void setHeaderLength(int headerLength) {
        this.headerLength = headerLength;
    }

    public int getPacketLength() {
        return packetLength;
    }

    public void setPacketLength(int packetLength) {
        this.packetLength = packetLength;
    }

    public int getSeq() {
        return seq;
    }



    public int getAck() {
        return ack;
    }

    public void setSeq(int seq) {
        this.seq = seq;
    }

    public void setAck(int ack) {
        this.ack = ack;
    }

    public byte[] getPayload() {
        return payload;
    }

    public void setPayload(byte[] payload) {
        this.payload = payload;
    }
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Packet)) return false;

        Packet packet = (Packet) o;

        if (ack != packet.ack) return false;
        if (headerLength != packet.headerLength) return false;
        if (magic != packet.magic) return false;
        if (packetLength != packet.packetLength) return false;
        if (seq != packet.seq) return false;
        if (type != packet.type) return false;
        if (version != packet.version) return false;
        if (!Arrays.equals(payload, packet.payload)) return false;

        return true;
    }

    @Override
    public int hashCode() {
        int result = magic;
        result = 31 * result + (int) version;
        result = 31 * result + (int) type;
        result = 31 * result + headerLength;
        result = 31 * result + packetLength;
        result = 31 * result + seq;
        result = 31 * result + ack;
        result = 31 * result + Arrays.hashCode(payload);
        return result;
    }

    @Override
    public String toString() {
        return "Packet{" +
                "magic=" + magic +
                ", version=" + version +
                ", type=" + type +
                ", headerLength=" + headerLength +
                ", packetLength=" + packetLength +
                ", seq=" + seq +
                ", ack=" + ack +
                ", payload=" + Arrays.toString(payload) +
                '}';
    }

}