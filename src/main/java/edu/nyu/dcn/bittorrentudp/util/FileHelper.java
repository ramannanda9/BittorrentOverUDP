package edu.nyu.dcn.bittorrentudp.util;

import edu.nyu.dcn.bittorrentudp.pojo.PeerAddress;
import org.apache.commons.codec.digest.DigestUtils;

import java.io.*;
import java.lang.reflect.Array;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/**
 * Created by Ramandeep Singh on 16-12-2015.
 */
public class FileHelper {


    private static BufferedWriter bufferedWriter;
    /**
     * To fetch the peer list
     * @param fileName the fileName to use
     * @return
     */
public static HashMap<Integer,PeerAddress> getPeerList(String fileName){

    HashMap<Integer,PeerAddress> peerList=new HashMap();
    try (BufferedReader br =
                 new BufferedReader(new FileReader(fileName))) {
        String line;
        while((line=br.readLine())!=null){
           String[] array= line.split("\\s+");
           if(array.length==3){
               PeerAddress peerAddress=new PeerAddress(array[1],Integer.parseInt(array[2]));
               peerList.put(Integer.parseInt(array[0]),peerAddress);
           }
        }
    }catch (IOException e){

    }
return peerList;
}

    /**
     * This method writes window size changes to the log file
     * in the temporary directory of the OS
     * @param message
     */
    public static void writeWindowChangeToLogFile(String message){
        try {
            bufferedWriter.write(message);
            bufferedWriter.newLine();
            bufferedWriter.flush();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * Invoked as a Shutdown Hook
     * to close the problem-peer file.
     */
    public static void closeFile(){
        if(bufferedWriter!=null){
            try {
                bufferedWriter.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * To create a problem-peer file
     * @param id the id of the file
     * @return BufferedWriter instance.
     */
    public static BufferedWriter setUpPeerProblemFile(int id){
         bufferedWriter=null;
        try {
            File file=  new File(System.getProperty("java.io.tmpdir")+File.separator+"problem"+id+"-peer.txt");
            FileWriter writer=new FileWriter(file);
            bufferedWriter=new BufferedWriter(writer);

        } catch (IOException e) {
            e.printStackTrace();
        }
      return bufferedWriter;
    }
    /**
     * To write the chunk to temporary directory
     * @param fileName fileName
     * @param dataArray the dataarray
     * @return
     */
    public static boolean writeChunkFile(String fileName,ArrayList<byte[]>dataArray){
        try (BufferedOutputStream bos=new BufferedOutputStream(new FileOutputStream(fileName))) {
        for(byte[]data:dataArray) {
            bos.write(data,0,data.length);
        }
        bos.flush();
            return true;
        }catch (IOException e){
         return false;
        }

    }

    /**
     * To fetch the chunks that are there in the file name
     * @param fileName the fileName to use
     * @return
     */
    public static LinkedHashMap<Integer,String> getChunksThatIHave(String fileName){
        LinkedHashMap<Integer,String> hashMap=new LinkedHashMap();
        try (BufferedReader br =
                     new BufferedReader(new FileReader(fileName))) {
            String line;
            while ((line = br.readLine()) != null) {
                String[] array = line.split("\\s+");
                if(array.length==2){
                    hashMap.put(Integer.parseInt(array[0]), array[1]);
                }

            }
        }catch (IOException e){

        }
        return hashMap;

    }

    /**
     * It reads from the master file and write them to the file in order specified.
     * @param chunkIdList the list of chunk ids to write
     * @param fileName the fileName on which to write
     * @param masterFileName the master chunk file name
     */
     public static void writeOutputFile(LinkedHashMap<Integer,String> chunkIdList,String fileName,String masterFileName){
         String masterDataFileName="";
         LinkedHashMap<String,Integer> offsets=new LinkedHashMap<>();

         try (BufferedReader br =
                      new BufferedReader(new FileReader(masterFileName))) {
             String line=br.readLine();
             masterDataFileName=line.split(":",2)[1].trim();
             br.readLine();
             while ((line = br.readLine()) != null) {
                 String array[]=line.split("\\s+");
                 for(Integer id:chunkIdList.keySet()) {
                    String chunkId=chunkIdList.get(id);
                     if (array[1].equals(chunkId)) {
                         offsets.put(chunkId, Integer.parseInt(array[0]));
                     }
                 }

             }
         }catch (IOException e){

         }


         LinkedHashMap<Integer,byte[]> fileChunks=new LinkedHashMap<>();

         try(BufferedInputStream bufferedInputStream=new BufferedInputStream(new FileInputStream(masterDataFileName))){
             int read;
             byte[] buffer=new byte[512*1024];
             int offset=0;
             while((read=bufferedInputStream.read(buffer))!=-1){
                 fileChunks.put(offset, Arrays.copyOfRange(buffer,0,read));
                 offset=offset+1;
             }
         }
         catch(IOException e){

         }
         try (BufferedOutputStream bos=new BufferedOutputStream(new FileOutputStream(fileName))) {
             for (Integer id : chunkIdList.keySet()) {
                 String chunkId = chunkIdList.get(id);
                 Integer masterChunkIdOffset = offsets.get(chunkId);
                 byte [] chunkValue=fileChunks.get(masterChunkIdOffset);
                 bos.write(chunkValue,0,chunkValue.length);
                 }
             bos.flush();
         }catch (IOException e){

         }
     }





    /**
     * This method makes chunks of 1500-24 bytes from file.
     * @return
     */
    public static ArrayList<byte[]> createChunksForFile(String masterFileName,String chunkId) {
        String fileName="";
        int offset=0;
        try (BufferedReader br =
                     new BufferedReader(new FileReader(masterFileName))) {
            String line=br.readLine();
            fileName=line.split(":",2)[1].trim();
            br.readLine();
            while ((line = br.readLine()) != null) {
             String array[]=line.split("\\s+");
             if(array[1].equals(chunkId)){
                 offset=Integer.parseInt(array[0]);
             }

            }
        }catch (IOException e){

        }


       ArrayList<byte[]> fileChunks=new ArrayList<>();
       int bytesToSkip=512*1024*offset;
        try(BufferedInputStream bufferedInputStream=new BufferedInputStream(new FileInputStream(fileName))){
            byte[] buffer =new byte[1476];
            int totalBytesToRead=512*1024;
            int bytesLeftToRead=totalBytesToRead;
            bufferedInputStream.skip(bytesToSkip);
            int read=0;
            while((read=bufferedInputStream.read(buffer))!=-1&&bytesLeftToRead>buffer.length){
                bytesLeftToRead=bytesLeftToRead-buffer.length;
                fileChunks.add(Arrays.copyOfRange(buffer,0,read));
            }
        fileChunks.add(Arrays.copyOfRange(buffer,0,bytesLeftToRead));
        }
        catch(IOException e){

        }
        return fileChunks;


    }

    public static int getSequenceNoFromMasterFile(String fileName,String chunkId){
        int masterChunkSequence=0;
        try (BufferedReader br =
                     new BufferedReader(new FileReader(fileName))) {
           br.readLine();br.readLine();
            String line="";
            while ((line = br.readLine()) != null) {
                String array[]=line.split("\\s+");
                if(array[1].equals(chunkId)){
                    masterChunkSequence=Integer.parseInt(array[0]);
                }

            }
        }catch (IOException e){

        }
       return masterChunkSequence;

    }

    public static void updateChunksThatIhave(int identity,String chunkId,int sequence){
       File fileName= new File(System.getProperty("java.io.tmpdir")+File.separator+"chunks-downloaded-peer-"+identity+".txt");
        try (BufferedWriter bufferedWriter=new BufferedWriter(new FileWriter(fileName))) {
            bufferedWriter.newLine();
            bufferedWriter.write(sequence+" "+chunkId);

    }catch (IOException e){
        }
    }





}
