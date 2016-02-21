package edu.nyu.dcn.bittorrentudp.core;

import groovy.util.GroovyTestCase;


import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public class PeerTest extends GroovyTestCase {

    public void testDecodePacket() throws Exception {
//        Packet packet=new Packet(15441,(short)1,(short)2,24,1024,12,13,new byte[1000]);
//        byte[]array=Peer.encodePacket(packet);
//        Packet packet1=Peer.decodePacket(array);
//        boolean compare=packet.equals(packet1);
//        System.out.println("compare = " + compare);
         String abc="a3c565f9e67e78bfb3fa6d783e5c5557f4011c98";
        int length=abc.getBytes(StandardCharsets.US_ASCII).length;
        System.out.println(length);
    }

    public void testEncodePacket() throws Exception{

    }
}