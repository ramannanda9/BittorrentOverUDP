package edu.nyu.dcn.bittorrentudp.util;

import edu.nyu.dcn.bittorrentudp.pojo.PeerAddress;
import groovy.util.GroovyTestCase;
import org.apache.commons.codec.digest.DigestUtils;


import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.nio.ByteBuffer;
import java.security.DigestInputStream;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Formatter;
import java.util.HashMap;

public class FileHelperTest extends GroovyTestCase {

    public void testGetPeerList() throws Exception {
     HashMap<Integer,PeerAddress> peerList= FileHelper.getPeerList(Thread.currentThread().getContextClassLoader().getResource("files/nodes.map").getFile());
        System.out.println("peerList = " + peerList);
    }


    public void testWriteChunkFile() throws Exception {

    }

    public void testGetChunksThatIHave() throws Exception {

    }

    public void testCreateChunksForFile() throws Exception {
       ArrayList<byte[]> output= FileHelper.createChunksForFile("C:\\Users\\resources\\files\\C.masterchunks", "70804C66F14A815488418EFB6268DFF6912A86D2");
       output.stream().forEach((byte[] b)->{System.out.println("-------------");});
        System.out.println("Output size"+output.size());
        ByteArrayOutputStream baos=new ByteArrayOutputStream();
        DataOutputStream dais=new DataOutputStream(baos);
        for(byte [] element:output){
            dais.write(element,0,element.length);
        }
        String digest= DigestUtils.sha1Hex(baos.toByteArray());
        System.out.println("digest = " + digest);







    }

}