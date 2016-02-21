package edu.nyu.dcn.bittorrentudp.core;

import edu.nyu.dcn.bittorrentudp.pojo.PeerAddress;
import edu.nyu.dcn.bittorrentudp.util.FileHelper;
import edu.nyu.dcn.bittorrentudp.util.SetupPeer;
import org.apache.commons.codec.digest.DigestUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Created by Ramandeep Singh on 16-12-2015.
 * <p/>
 * This class represents a peer
 * This is the core class containing logic behind the BITTORRENT OVER UDP implementation.
 */
public class Peer {
    private String peerFile;
    private String chunkFile;
    private static int maximumConnections = 512;
    private static AtomicInteger availableConnections ;
    private String masterChunkFile;
    private int peerIdentity;
    private Future listenerTask = null;
    private static  ExecutorService executor=null;
    private static DatagramSocket socket;
    private String lastFailedNode=null;
    private static HashMap<String, ReliabilityAndCongestionHandler> reliabilityHandler;
    private static HashMap<Integer, PeerAddress> peerList;
    private LinkedHashMap<Integer, String> userRequestedChunks;
    private static ConcurrentHashMap<String, ArrayList<String>> chunkNodes = new ConcurrentHashMap <String, ArrayList<String>>();
    private LinkedHashMap<Integer, String> chunkMap;
    private HashSet<String> chunksToDownload;
    private static ConcurrentHashMap<String,NodeChunkDate> nodesDownloadingFrom;
    private static ConcurrentHashMap<String,NodeChunkDate> nodesUploadingTo;
    private static CopyOnWriteArrayList<String> chunksBeingDownloaded;
    private String outputFileName;
    private static final Date startTime=new Date(System.currentTimeMillis());
    private static final Logger logger = LoggerFactory.getLogger(Peer.class);

    public Peer(String peerFile, String chunkFile, int maximumConnections, String masterChunkFile, int peerIdentity) {
        this.peerFile = peerFile;
        this.chunkFile = chunkFile;
        this.maximumConnections = maximumConnections;
        availableConnections=new AtomicInteger(maximumConnections);
        this.masterChunkFile = masterChunkFile;
        this.peerIdentity = peerIdentity;
        nodesUploadingTo=new ConcurrentHashMap<>();
        FileHelper.setUpPeerProblemFile(peerIdentity);
        //take the port and host name from the nodes.map file to listen on that port
        peerList = FileHelper.getPeerList(peerFile);
        chunkMap = FileHelper.getChunksThatIHave(chunkFile);
        createUDPSocket(peerList.get(peerIdentity));
        reliabilityHandler=new HashMap<>();
        peerList.remove(peerIdentity);
        if(SetupPeer.debugLevel>=2) {
            logger.info("Peer {} Initialized ", peerIdentity);
        }

    }


    /**
     * Starts the socket listener.
     */
    public void startListeningForResponse(){
         executor = deamonSingleThreadService();
       Runnable listenThread = () -> {
            startListening();
        };

        listenerTask = executor.submit(listenThread);



    }

    /**
     * This is the socket listener which listens for the incoming packet.
     * And forwards the request to classifyResponsePacket method.
     */
    private void startListening() {

        while (true) {
            byte[] receiveData = new byte[1500];
            DatagramPacket packet = new DatagramPacket(receiveData, receiveData.length);
            try {
                        socket.receive(packet);
                        classifyResponsePacket(packet);
                 } catch (IOException e) {
                if (SetupPeer.debugLevel >= 1) {
                    logger.error("An error occurred ", e);
                }
            }

        }
    }

    private String getIdentifierFromPacket(DatagramPacket packet) {
        return packet.getAddress().getHostAddress() + ":" + packet.getPort();
    }

    /**
     * Classifies response packet into one of the PACKET types.
     * @param packet
     */
    private void classifyResponsePacket(DatagramPacket packet) {
        byte[] data = packet.getData();
        Packet dataPacket = decodePacket(data);
        String peerIdentifier = getIdentifierFromPacket(packet);

        if (dataPacket.getMagic() != Packet.MAGIC_NUMBER || dataPacket.getVersion() != Packet.VERSION_NUMBER) {
            return;
        }

        switch (dataPacket.getType()) {
            case 0: {
                //who has request
                if(canIAcceptNewConnections()) {
                    handleWhoHasRequest(dataPacket, packet);
                }
                break;
            }
            case 1: {

                //handleIhaveResponse
                if(canIAcceptNewConnections()) {
                    handleIHaveResponse(dataPacket, packet);
                }
                break;

            }
            case 2: {
                //get request from peer so upload
                if(canIAcceptNewConnections()) {
                    handleUploadRequest(dataPacket, packet);
                }
                break;
            }
            case 3: {

                handleDataResponse(dataPacket, packet);
                break;
            }
            case 4: {
                handleAckResponse(dataPacket, packet);
                break;
            }
            case 5: {
                handleDenyResponse(dataPacket, packet);
                break;
            }


        }


    }

    /**
     * To check whether this peer can accept connection
     * @return True if it can else false
     */
    private boolean canIAcceptNewConnections() {
            if(availableConnections.get()>0){
                return true;
            }
        return false;
    }

    /**
     * To handle the response to receiving a packet.
     * Probably sending an ACK till the download is complete.
     * @param dataPacket
     * @param packet
     */
    private void handleDataResponse(Packet dataPacket, DatagramPacket packet) {
        int sequenceNumber=dataPacket.getSeq();
        String peerAddress= getIdentifierFromPacket(packet);
        if(reliabilityHandler!=null&&reliabilityHandler.get(peerAddress)!=null){
           ReliabilityAndCongestionHandler handler= reliabilityHandler.get(peerAddress);
           //Since this is not selective repeat we verify that packets must be received in sequence
           //Although it can easily be changed to selective repeat now
           //But not implementing the feature as not requested
           //For now if the packet is duplicate not in sequence it will be rejected
           int lastPacketInSequence= handler.getLastContiguousSequencePacket();
           if(sequenceNumber-lastPacketInSequence>1|| lastPacketInSequence>=sequenceNumber){
//               handler.resendAck();
               return;
           }
           handler.getDataBuffer().add(sequenceNumber,dataPacket.getPayload());
           handler.setLastPacketRcvd(sequenceNumber);
           handler.sendAck();
           String currentChunkId=handler.getCurrentChunkId();
           if(handler.getDataBuffer().size()>=356){
               String sha1Hash= calculateSHA1Hash(handler.getDataBuffer());
               handler.stopMonitoring();
              //Hashes don't match reinitiate who has request
               if(!sha1Hash.equalsIgnoreCase(currentChunkId)){
                   nodesDownloadingFrom.remove(peerAddress);
                   chunksBeingDownloaded.remove(currentChunkId);
                   issueWhoHasRequest(new String[]{handler.getCurrentChunkId()});
               }
               //This means that the file is valid so save it to a file
               //Update has chunk
               //Remove it from the list of chunksTobeDownloaded
               else{
                   nodesDownloadingFrom.remove(peerAddress);
                   chunksBeingDownloaded.remove(currentChunkId);
                   chunksToDownload.remove(currentChunkId);
                   int masterChunkSequence=FileHelper.getSequenceNoFromMasterFile(masterChunkFile, currentChunkId);
                   FileHelper.updateChunksThatIhave(peerIdentity, currentChunkId, masterChunkSequence);
                   chunkMap.put(masterChunkSequence,currentChunkId);
                   String tempDirectory=System.getProperty("java.io.tmpdir");
                   String fileName=tempDirectory+ File.separator+currentChunkId;
                   //Optional just writing it down anyways
                   FileHelper.writeChunkFile(fileName,handler.getDataBuffer());



               }
               //Either ways do the cleanup
               handler.isDownloading=false;
               handler.stopMonitoring();
               reliabilityHandler.remove(peerAddress);
               increaseAvailableConnectionCount();
           }
        }


    }

    /**
     * Just handle the deny response
     * Since peers do not implement this, so ignore it.
     * @param dataPacket
     * @param packet
     */
    private void handleDenyResponse(Packet dataPacket, DatagramPacket packet) {
        if (SetupPeer.debugLevel >= 1) {
            logger.info("Request denied by ", packet.getAddress() + "--" + packet.getPort());
            //As we are not sure for which chunk the server responded we just ignore it
        }
    }

    /**
     * Handles ACK response returns A data packet
     * @param dataPacket
     * @param packet
     */
    private void handleAckResponse(Packet dataPacket, DatagramPacket packet) {
        String address=getIdentifierFromPacket(packet);
        if(reliabilityHandler!=null&&reliabilityHandler.get(address)!=null) {
            int packetAcked = dataPacket.getAck();
            ReliabilityAndCongestionHandler handler = reliabilityHandler.get(address);
            if(handler!=null) {
                //Marks completion of upload
                if (handler.getLastPacketAcked()>= 354) {
                    handler.setUploading(false);
                    handler.stopMonitoring();
                    nodesUploadingTo.remove(getIdentifierFromPacket(packet));
                    //Marks completion of upload
                    reliabilityHandler.remove(address);
                    increaseAvailableConnectionCount();
                } else {
                    if (packetAcked <= handler.getLastPacketAcked()) {
                        //Just ignore the ack as duplicate for 3 times
                        if (handler.getDuplicateAckCount().get() < 3) {
                            handler.getLastTimeAckReceived().set(System.currentTimeMillis());
                            handler.getDuplicateAckCount().incrementAndGet();
                            handler.getFailureCount().set(0);
                        } else {
                            //SEND the packet FAST RETRANSMIT
                            handler.getLastTimeAckReceived().set(System.currentTimeMillis());
                            handler.getDuplicateAckCount().set(0);
                            handler.resendDataPacket();
                            handler.packetLostSetshThreshold();
                            handler.getFailureCount().set(0);
                        }
                    } else if (packetAcked > handler.getLastPacketAcked()) {
                        handler.setLastPacketAcked(packetAcked);
                        handler.sendDataPacket();
                        handler.increaseWindowSize();

                    }
                }
            }

        }
    }

    /**
     * This is the method where we store which node has access to what chunk id
     * Since there might be multiple nodes that have the same chunk id
     * This seems a tradeof between parallel scale
     *
     * @param dataPacket
     * @param packet
     */
    private void handleIHaveResponse(Packet dataPacket, DatagramPacket packet) {
        String[] extractedChunkIds = extractChunkIds(dataPacket.getPayload());
        String address = packet.getAddress().getHostAddress();
        String port = String.valueOf(packet.getPort());
        for (String chunkId : extractedChunkIds) {
               if (!chunkNodes.containsKey(chunkId)) {
                    ArrayList hosts = new ArrayList<String>();
                    hosts.add(address + ":" + port);
                    chunkNodes.put(chunkId, hosts);
                } else {
                    chunkNodes.get(chunkId).add(address + ":" + port);
                }
         }


    }

    /**
     * To handle the who has request
     * @param dataPacket
     * @param packet
     */
    private void handleWhoHasRequest(Packet dataPacket, DatagramPacket packet) {
        String[] chunkIds = extractChunkIds(dataPacket.getPayload());
        String nodeIdentifier=getIdentifierFromPacket(packet);
        if(nodesDownloadingFrom!=null){
            if(nodesDownloadingFrom.contains(nodeIdentifier)) {
                return;
            }
        }
            ArrayList<String> chunkIdsThatIHave = new ArrayList<>();
            for (String chunkId : chunkIds) {
                if (checkIfIhaveChunk(chunkId)) {
                    chunkIdsThatIHave.add(chunkId);
                }
            }
            if (chunkIdsThatIHave.size() > 0) {
                createIhaveResponse(packet, chunkIdsThatIHave);
            }


    }

    /**
     * Creates an IHAVE response. For the nodes this node has access to.
     * @param packet
     * @param chunkIdsThatIHave The ID's that peer has access to
     */
    private void createIhaveResponse(DatagramPacket packet, ArrayList<String> chunkIdsThatIHave) {
        int magicNumber = Packet.MAGIC_NUMBER;
        short versionNumber = Packet.VERSION_NUMBER;
        short type = (short) Packet.PACKETTYPE.IHAVE.ordinal();
        int headerLength = Packet.HEADER_LENGTH;
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream daos = new DataOutputStream(baos);
        try {
            daos.writeShort(chunkIdsThatIHave.size());
            daos.writeShort(0);
            for (String chunkId : chunkIdsThatIHave) {
                daos.write(chunkId.getBytes());
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        byte[] payLoad = baos.toByteArray();
        Packet dataPacket = new Packet(magicNumber, versionNumber, type, headerLength, headerLength + payLoad.length, 0, 0, payLoad);
        try {
            packet.setData(encodePacket(dataPacket));
            socket.send(packet);
        } catch (IOException e) {
            if (SetupPeer.debugLevel >= 1) {
                logger.error("Unable to send I have request");
            }
        }

    }

    private boolean checkIfIhaveChunk(String chunkId) {
        return chunkMap.containsValue(chunkId);
    }

    /**
     * For extracting the CHUNK ids's from payload of WHOHAS request
     * OR IHAVE response.
     * @param payload
     * @return
     */
    private String[] extractChunkIds(byte[] payload) {

        short chunkCount = ByteBuffer.wrap(payload, 0, 2).getShort();
        String[] chunkIds = new String[chunkCount];
        int offset = 4;

        for (int i = 0; i < chunkCount; i++) {
            chunkIds[i] = new String(Arrays.copyOfRange(payload, offset, offset + Packet.CHUNK_HASH_SIZE),StandardCharsets.US_ASCII);
            offset = offset+Packet.CHUNK_HASH_SIZE;
        }
        return chunkIds;
    }

    /**
     * For decoding the packet instance from payload data received from DatagramPacket
     * @param data
     * @return the decoded Packet Instance
     */
    public static Packet decodePacket(byte[] data) {
        byte[] magicNumberArray = Arrays.copyOfRange(data, 0, 4);
        int magicNumber = ByteBuffer.wrap(magicNumberArray).getInt();
        byte[] versionArray = Arrays.copyOfRange(data, 4, 6);
        short versionNumber = ByteBuffer.wrap(versionArray).getShort();
        byte[] typeArray = Arrays.copyOfRange(data, 6, 8);
        short type = ByteBuffer.wrap(typeArray).getShort();
        byte[] headerLengthArray = Arrays.copyOfRange(data, 8, 12);
        int headerLength = ByteBuffer.wrap(headerLengthArray).getInt();
        byte[] packetLengthArray = Arrays.copyOfRange(data, 12, 16);
        int packetLength = ByteBuffer.wrap(packetLengthArray).getInt();
        byte[] seqArray = Arrays.copyOfRange(data, 16, 20);
        int seq = ByteBuffer.wrap(seqArray).getInt();
        byte[] ackArray = Arrays.copyOfRange(data, 20, 24);
        int ack = ByteBuffer.wrap(ackArray).getInt();
        byte[] payLoadArray = Arrays.copyOfRange(data, 24, packetLength);
        Packet packet = new Packet(magicNumber, versionNumber, type, headerLength, packetLength, seq, ack, payLoadArray);
        return packet;
    }

    /**
     * To compute the SHA-1 hash of data array.
     * @param dataArray
     */
    public static String calculateSHA1Hash(ArrayList<byte[]> dataArray){
        ByteArrayOutputStream baos=new ByteArrayOutputStream();
        DataOutputStream daos=new DataOutputStream(baos);
        for(byte[] data :dataArray){
            try {
                daos.write(data,0,data.length);
            } catch (IOException e) {

            }
        }
     return   DigestUtils.sha1Hex(baos.toByteArray());

    }

    /**
     * For encoding packet data as payload into the DatagramPacket
     * @param packet the packet instance
     * @return encoded byte array
     */
    public static byte[] encodePacket(Packet packet)  {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(packet.getPacketLength());
        DataOutputStream dos = new DataOutputStream(bos);
        try {
            dos.writeInt(packet.getMagic());
            dos.writeShort(packet.getVersion());
            dos.writeShort(packet.getType());
            dos.writeInt(packet.getHeaderLength());
            dos.writeInt(packet.getPacketLength());
            dos.writeInt(packet.getSeq());
            dos.writeInt(packet.getAck());
            if (packet.getPayload() != null) {
                dos.write(packet.getPayload(), 0, packet.getPayload().length);
            }
        }catch (IOException e){
            if(SetupPeer.debugLevel>=1){
                logger.error("Unable to encode the packet");
            }
        }
        return bos.toByteArray();

    }


    private static void reduceAvailableConnectionCount() {
          availableConnections.decrementAndGet();
            if(SetupPeer.debugLevel>=2) {
                logger.info(Thread.currentThread().getStackTrace()[2].getMethodName()+"Reduced Connections available connection {}", availableConnections.get());
            }


    }

    private static void increaseAvailableConnectionCount() {
            availableConnections.incrementAndGet();
            if(SetupPeer.debugLevel>=2) {
                logger.info(Thread.currentThread().getStackTrace()[2].getMethodName()+"Increased available connection {}", availableConnections.get());
            }


    }

    /**
     * Creates a udp socket
     * @param address the address of the node.
     */
    private void createUDPSocket(PeerAddress address) {
      try {
            try {
                socket = new DatagramSocket(address.getPort(), InetAddress.getByName(address.getHostName()));
            } catch (UnknownHostException e) {
                throw new RuntimeException("Unable to bind socket on host", e);
            }
        } catch (SocketException e) {
            throw new RuntimeException("Unable to bind socket to host", e);
        }
    }

    /**
     * Handles upload request i.e. DATA upload request
     * @param packet the packet decoded from the socket
     * @param datagramPacket the datagram packet instance.
     */

    public void handleUploadRequest(Packet packet, DatagramPacket datagramPacket) {


        String peerAddress= getIdentifierFromPacket(datagramPacket);
        String chunkId=new String(packet.getPayload());
        if(checkIfIhaveChunk(chunkId)) {
     //ensures that the peer is not already downloading or we are not uploading
     if(reliabilityHandler.get(peerAddress)==null||
             !reliabilityHandler.get(peerAddress).isUploading){
         ReliabilityAndCongestionHandler reliabilityAndCongestionHandler=
                 new ReliabilityAndCongestionHandler(getPeerIdentityFromAddress(peerAddress),peerAddress,1,chunkId);
         ArrayList<byte[]> dataArray= FileHelper.createChunksForFile(masterChunkFile,chunkId);
         ArrayList<DatagramPacket> datagramPacketArray=new ArrayList<>();
         int i=0;
         for(byte[] data:dataArray){
             byte [] payloadData=createDataPacket(data,i);
             DatagramPacket datagramPacketToAdd=new DatagramPacket(payloadData,0,payloadData.length,datagramPacket.getAddress(),datagramPacket.getPort());
             i=i+1;
             datagramPacketArray.add(datagramPacketToAdd);
         }
         reliabilityAndCongestionHandler.setUploadBuffer(datagramPacketArray);
         reliabilityAndCongestionHandler.setCurrentChunkId(chunkId);
         reliabilityAndCongestionHandler.setUploading(true);
         reliabilityAndCongestionHandler.startMonitoring();
         reliabilityHandler.put(peerAddress,reliabilityAndCongestionHandler);
         reliabilityAndCongestionHandler.sendDataPacket();
         reduceAvailableConnectionCount();
         nodesUploadingTo.put(peerAddress,new NodeChunkDate(chunkId));
         }
     }

    }

    /**
     * Creates the data packet
     * @param data the byte[] data
     * @param i the sequence number to use
     * @return
     */
    private byte[] createDataPacket(byte[] data, int i) {
        int magicNumber = Packet.MAGIC_NUMBER;
        short versionNumber = Packet.VERSION_NUMBER;
        short type = (short) Packet.PACKETTYPE.DATA.ordinal();
        int headerLength = Packet.HEADER_LENGTH;
        int packetLength=data.length+headerLength;
        int sequenceNo=i;
        int ackNo=0;
        Packet packet=new Packet(magicNumber,versionNumber,type,headerLength,packetLength,sequenceNo,ackNo,data);
       return encodePacket(packet);

    }

    /**
     * This method handles the get request
     * if it is requested by the client.
     * It first checks if the file is present or not
     * If it is present then it just dumps the file into the temp directory
     * else issues a broadcast flood to other peers with the chunk names
     * from master file.
     * to ask from them who has the file, retrieves it and then dumps it.
     *
     * @param fileName       the requested filename
     * @param outputFileName the output filename requested
     */
    public void handleGetRequestFromClient(String fileName, String outputFileName) {
        //The name is a misnomer here it asks for the chunks that are there in the filename
        userRequestedChunks = FileHelper.getChunksThatIHave(fileName);
        this.outputFileName=outputFileName;
        HashSet requestedChunkIds = new HashSet();
        requestedChunkIds.addAll(userRequestedChunks.values());
        HashSet possesedChunkIds = new HashSet();
        possesedChunkIds.addAll(chunkMap.values());
        requestedChunkIds.removeAll(possesedChunkIds);
        chunksToDownload = requestedChunkIds;
        chunksBeingDownloaded=new CopyOnWriteArrayList<>();

        while(chunkNodes.isEmpty()&&!checkAllNodesPresent(chunkNodes,chunksToDownload)){
            issueWhoHasRequestsForChunkIds();
            try {
                int sleepInterval=ThreadLocalRandom.current().nextInt(5000,8000);
                //This also avoids collision chances and Race Conditions :)
                Thread.sleep(sleepInterval);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
        createGetRequests();
        //Got all the files now just write the file to the outputfile
        FileHelper.writeOutputFile(userRequestedChunks,outputFileName,masterChunkFile);
        logger.info("GOT CHUNK {}",outputFileName);

    }

    /**
     * Helper method to check whether IHave response for all the chunk ids have been received.
     * @param chunkNodes
     * @param requestedChunkIds
     * @return
     */
    private boolean checkAllNodesPresent(ConcurrentHashMap<String, ArrayList<String>> chunkNodes, HashSet<String> requestedChunkIds) {

        for(String value:requestedChunkIds){
            if(!chunkNodes.contains(value)){
                return false;
            }

        }
        return true;
    }

    /**
     * This method creates GET requests for multiple chunk ids simultaneously
     */
    private void createGetRequests() {

            nodesDownloadingFrom = new ConcurrentHashMap<>();
            while (!chunksToDownload.isEmpty()) {
                removeStaleNodesAndChunks();
                    //Now issue parallel gets for chunk ids
                    for (String chunkId : chunkNodes.keySet()) {
                        if (chunksToDownload.contains(chunkId) && !chunksBeingDownloaded.contains(chunkId)) {
                            ArrayList<String> nodes = chunkNodes.get(chunkId);
                            for (String node : nodes) {
                                if (!nodesDownloadingFrom.keySet().contains(node)&&!nodesUploadingTo.keySet().contains(node)) {
                                    issueGetRequest(chunkId, node);
                                    nodesDownloadingFrom.put(node,new NodeChunkDate(chunkId));
                                    chunksBeingDownloaded.add(chunkId);
                                    break;
                                }
                            }
                        }
                    }
                    try {
                        //This ensures we give sufficient time to download the packets
                        //also to readd the chunks in case it fails.
                        Thread.sleep(2000);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }




    }

    /**
     * This is for ensuring reliability of GET requests.
     * If the node is not finished downloading within 60 seconds
     * it is removed and the WhoHasRequest is reissued for the chunk id.
     */
    private void removeStaleNodesAndChunks() {
        Long value=System.currentTimeMillis();
        if(nodesDownloadingFrom!=null) {
            for (String nodeAddress : nodesDownloadingFrom.keySet()) {
                AtomicLong nodeStartTime = nodesDownloadingFrom.get(nodeAddress).getNodeStartDate();
                String chunkId = nodesDownloadingFrom.get(nodeAddress).getChunkId();
                if (value - nodeStartTime.get() > 600000) {
                    nodesDownloadingFrom.remove(nodeAddress);
                    chunksBeingDownloaded.remove(chunkId);
                    increaseAvailableConnectionCount();
                    for (String chunkIdRemoveNode : chunkNodes.keySet()) {
                        if (chunkNodes.get(chunkIdRemoveNode).remove(nodeAddress)) {
                            issueWhoHasRequest(chunksToDownload.toArray(new String[chunksToDownload.size()]));
                        }
                    }
                }

            }
        }

    }
    //TODO remove this is not used.
    private boolean nodeAlive(String node) {
        for(Integer key: peerList.keySet()){
           PeerAddress peerAddress= peerList.get(key);
          if(node.equalsIgnoreCase(peerAddress.getHostName() + ":" + peerAddress.getPort())) {
                return true;
            }
        }
        return false;
    }

    /**
     * To get peer identity from address.
     * @param address
     * @return
     */
    private int getPeerIdentityFromAddress(String address){
        for(Integer key: peerList.keySet()){
            PeerAddress peerAddress= peerList.get(key);
            if(address.equalsIgnoreCase(peerAddress.getHostName() + ":" + peerAddress.getPort())) {
                return key;
            }
        }
        return 0;
    }

    /**
     * This method creates and issues a GET request for the peer.
     * @param chunkId The chunk id that is requested.
     * @param nodeAddress the nodeaddress to be used.
     */
    private void issueGetRequest(String chunkId, String nodeAddress) {
        if (chunksToDownload.contains(chunkId)) {
            String hostAddress = nodeAddress.split(":")[0];
            String port = nodeAddress.split(":")[1];
            int magicNumber = Packet.MAGIC_NUMBER;
            short versionNumber = Packet.VERSION_NUMBER;
            short type = (short) Packet.PACKETTYPE.GET.ordinal();
            int headerLength = Packet.HEADER_LENGTH;
            int seq = 0;
            byte[] payload = chunkId.getBytes(StandardCharsets.US_ASCII);
            int packetLength = headerLength + payload.length;
            Packet packet = new Packet(magicNumber, versionNumber, type, headerLength, packetLength, seq, 0, payload);
            try {
                byte[] dataPayload = encodePacket(packet);
                DatagramPacket datagramPacket = new DatagramPacket(dataPayload, 0, dataPayload.length, InetAddress.getByName(hostAddress), Integer.parseInt(port));
                socket.send(datagramPacket);
                ReliabilityAndCongestionHandler handler=
                        new ReliabilityAndCongestionHandler(getPeerIdentityFromAddress(nodeAddress),nodeAddress,1,chunkId);
                handler.setDownloading(true);
                handler.startMonitoring();
                reliabilityHandler.put(nodeAddress,handler);
                reduceAvailableConnectionCount();
            } catch (IOException e) {
                if (SetupPeer.debugLevel >= 1) {
                    logger.error("Unable to send get request", e);
                }
            }

        }
    }

    /**
     * Creates an executor service instance
     * which spawns daemon threads.
     * These thread shut down when the program exits
     * To prevent memory and resource leak.
     * @return
     */
    public static  ExecutorService deamonSingleThreadService(){
        return Executors.newFixedThreadPool(1,new ThreadFactory(){

            @Override
            public Thread newThread(Runnable r) {
                Thread t=Executors.defaultThreadFactory().newThread(r);
                t.setDaemon(true);
                return t;
            }
        });
    }

    /**
     * This method partitions and creates multiple WHOHAS requests
     * if the number of chunks exceed the packet size.
     */
    private void issueWhoHasRequestsForChunkIds() {
        //check whether to split the packet
        ArrayList<String> chunkList = new ArrayList<>(chunksToDownload);
        int chunksToDownloadSize = chunksToDownload.size();
        int maximumChunkSizeAllowed = Packet.MAX_PACKET_SIZE - Packet.HEADER_LENGTH - 4;
        int totalChunksRequestSize = chunksToDownloadSize * Packet.CHUNK_HASH_SIZE;
        if (totalChunksRequestSize > maximumChunkSizeAllowed) {
            //We need to create multiple sets of who has requests
            //but not to the node from which we are downloading
            int requestCount = (int) Math.ceil((double) totalChunksRequestSize / maximumChunkSizeAllowed);
            int partitionSize = chunkList.size() / requestCount;
            List<List<String>> partitions = new LinkedList<List<String>>();
            for (int i = 0; i < chunkList.size(); i += partitionSize) {
                partitions.add(chunkList.subList(i,
                        Math.min(i + partitionSize, partitionSize)));
            }
            for (List<String> partition : partitions) {
                issueWhoHasRequest((String[]) partition.toArray(new String[partition.size()]));
            }
        } else {
            issueWhoHasRequest( chunkList.toArray(new String[chunkList.size()]));
        }

    }


    /**
     * This method issues the who has request
     * It floods the peers with this message
     *
     * @param chunkIds
     */
    public static void issueWhoHasRequest(String[] chunkIds) {
        //when a IHave reply comes then issue a get request to this node
        //fetch from this node the chunk id that it returned.
        int magicNumber = Packet.MAGIC_NUMBER;
        short versionNumber = Packet.VERSION_NUMBER;
        short type = (short) Packet.PACKETTYPE.WHOHAS.ordinal();
        int headerLength = Packet.HEADER_LENGTH;
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream daos = new DataOutputStream(baos);
        try {
            daos.writeShort((short) chunkIds.length);
            //add padding
            daos.writeShort(0);

            for (String chunkId : chunkIds) {
                daos.write(chunkId.getBytes(StandardCharsets.US_ASCII));
            }
            byte[] payload = baos.toByteArray();
            int packetLength = payload.length + headerLength;
            Packet packet = new Packet(magicNumber, versionNumber, type, headerLength, packetLength, 0, 0, payload);
            byte[] packetBuffer = encodePacket(packet);
            for (Integer peerId : peerList.keySet()) {
                PeerAddress peerAddress = peerList.get(peerId);
                String peerIden=peerAddress.getHostName()+":"+peerAddress.getPort();
                if(nodesUploadingTo.get(peerIden)==null) {
                    DatagramPacket datagramPacket = new DatagramPacket(packetBuffer, 0, packetBuffer.length, InetAddress.getByName(peerAddress.getHostName()), peerAddress.getPort());
                    socket.send(datagramPacket);
                }
            }
        } catch (IOException e) {
            if (SetupPeer.debugLevel >= 1) {
                logger.error("An error occurred while issuing who has request", e);
            }

        }



    }
    public static  void removeBadPeerAndReissueWhohas(String peerAddress,int peerId,String chunkId){
    //peerlist remove
//       peerList.remove(peerId);
        nodesDownloadingFrom.remove(peerAddress);
        chunksBeingDownloaded.remove(chunkId);
        increaseAvailableConnectionCount();
        reliabilityHandler.get(peerAddress).stopMonitoring();
        reliabilityHandler.remove(peerAddress);
        issueWhoHasRequest(new String[]{chunkId});


  }
    public static  void removeUnrespondingPeerUpload(String peerAddress){
        nodesUploadingTo.remove(peerAddress);
        reliabilityHandler.get(peerAddress).stopMonitoring();
        reliabilityHandler.remove(peerAddress);
        increaseAvailableConnectionCount();

    }

    /**
     * The heart of reliability for DATA and ACK requests.
     */
    private static final class ReliabilityAndCongestionHandler {
        private int peerId;
        private String peerAddress;
        private boolean isPeerAlive;
        private String currentChunkId;
        private boolean isDownloading;
        private boolean isUploading;
        private String hostAddress;
        private int port;
        private  ExecutorService threadPoolExecutor;
        private ArrayList<DatagramPacket> uploadBuffer = new ArrayList<>();
        private final String connectId;

        public ArrayList<byte[]> getDataBuffer() {
            return dataBuffer;
        }

        public void setDataBuffer(ArrayList<byte[]> dataBuffer) {
            this.dataBuffer = dataBuffer;
        }



        private ArrayList<byte[]> dataBuffer = new ArrayList<>();
        private AtomicLong lastTimeDataPacketReceived;
        private AtomicLong lastTimeAckReceived;
        private boolean isDownloadComplete;
        private int windowSize;
        private AtomicInteger ssThresh=new AtomicInteger(64);
        private Runnable runnable;
        //Sender Side
        private int lastPacketAcked = -1;
        private int lastPacketSent = 0;
        private int lastPacketAvailable = windowSize - lastPacketAcked;
        //Receiver side
        private int lastPacketRcvd;
        private int nextPacketExpected;

        public AtomicInteger getFailureCount() {
            return failureCount;
        }

        public void setFailureCount(AtomicInteger failureCount) {
            this.failureCount = failureCount;
        }

        private AtomicInteger failureCount=new AtomicInteger(0);
        private static final int maximumFailureCount = 3;
        private Future longRunningTaskFuture = null;


        public AtomicInteger getDuplicateAckCount() {
            return duplicateAckCount;
        }

        public void setDuplicateAckCount(AtomicInteger duplicateAckCount) {
            this.duplicateAckCount = duplicateAckCount;
        }

        private AtomicInteger duplicateAckCount=new AtomicInteger(0);

        public AtomicLong getLastTimeDataPacketReceived() {
            return lastTimeDataPacketReceived;
        }

        public void setLastTimeDataPacketReceived(AtomicLong lastTimeDataPacketReceived) {
            this.lastTimeDataPacketReceived = lastTimeDataPacketReceived;
        }

        public AtomicLong getLastTimeAckReceived() {
            return lastTimeAckReceived;
        }

        public void setLastTimeAckReceived(AtomicLong lastTimeAckReceived) {
            this.lastTimeAckReceived = lastTimeAckReceived;
        }

        public AtomicInteger getSsThresh() {
            return ssThresh;
        }

        public void setSsThresh(AtomicInteger ssThresh) {
            this.ssThresh = ssThresh;
        }

        //
        private long timeoutInMillis = 5000;

        public ReliabilityAndCongestionHandler(int peerId, String peerAddress, int windowSize,String currentChunkId) {
            this.peerId = peerId;
            this.peerAddress = peerAddress;
            this.isPeerAlive = true;
            this.windowSize = windowSize;
            this.hostAddress=peerAddress.split(":")[0];
            this.port=Integer.parseInt(peerAddress.split(":")[1]);
            this.currentChunkId=currentChunkId;
            this.connectId=peerAddress+":"+currentChunkId;


        }

        /**
         * To start monitoring of upload and download.
         * Depends on the current use of the peer.
         */
        public void startMonitoring() {
            runnable = () -> {
                monitorTimeOuts();
            };
            threadPoolExecutor=deamonSingleThreadService();
            longRunningTaskFuture = threadPoolExecutor.submit(runnable);
            lastTimeDataPacketReceived=new AtomicLong(System.currentTimeMillis());
            lastTimeAckReceived=new AtomicLong(System.currentTimeMillis());
        }

        /**
         * To stop monitoring of the connection
         * either due to successful completion
         * or due to unresponsive peers and failure.
         */
        public void stopMonitoring() {
            longRunningTaskFuture.cancel(true);
            threadPoolExecutor.shutdown();

        }


        /**
         * To monitor and track unresponding peers and reissuing requests
         * runs on a separate monitor thread.
         */
        private void monitorTimeOuts() {
            while (true) {
              AtomicLong currentTime=new AtomicLong(System.currentTimeMillis());
                if (isDownloading) {
                    if (currentTime.get()- lastTimeDataPacketReceived.get() >= timeoutInMillis) {
                        if (failureCount.get() >= maximumFailureCount) {
                            isPeerAlive=false;
                            isDownloading=false;
                            removeBadPeerAndReissueWhohas(peerAddress,peerId,currentChunkId);

                        }
                        //maybe resend ack and pause
                        else {
                            incrementFailureCount();
                            lastTimeDataPacketReceived.set(System.currentTimeMillis());
                        }
                    }

                }
                if(isUploading){
                    if(currentTime.get()-lastTimeAckReceived.get()>timeoutInMillis){
                        if (failureCount.get() >= maximumFailureCount) {
                            isUploading=false;
                            isPeerAlive = false;
                            removeUnrespondingPeerUpload(peerAddress);

                        }
                        else {
                            packetLostSetshThreshold();
                            resendDataPacket();
                            incrementFailureCount();
                        }
                    }

                }
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException e) {

                }
            }

        }

        /**
         * This method is used to increase the window size till the slow start threshold.
         * Only when successful ACK's are received.
         */
        public void increaseWindowSize(){
            if(windowSize<ssThresh.get()){
                long timeSinceStart=new Date(System.currentTimeMillis()).getTime()-startTime.getTime();
                windowSize=windowSize+1;
                String messageToLog=connectId+" "+timeSinceStart+" "+windowSize;
                FileHelper.writeWindowChangeToLogFile(messageToLog);

            }

        }

        /**
         * Sets the window size to 1 and reduces the Slow start threshold.
         * This is done in case there is packet loss.
         * Logs message to the problem-peer*.txt
         * where * is the id of the peer.
         */
        public void packetLostSetshThreshold(){

                ssThresh.set(Math.max(windowSize / 2, 2));
                windowSize=1;
                long timeSinceStart=new Date(System.currentTimeMillis()).getTime()-startTime.getTime();
                String messageToLog=connectId+" "+timeSinceStart+" "+windowSize;
                FileHelper.writeWindowChangeToLogFile(messageToLog);

        }

        /**
         * To resend the data packet.
         * Updates the @Field{lastTimeAckReceived} to present value of EPOCH time.
         */
        private void resendDataPacket() {
           lastTimeAckReceived.set( System.currentTimeMillis());
            DatagramPacket datagramPacket=uploadBuffer.get(lastPacketAcked+1);
            try {
                synchronized (socket) {
                    socket.send(datagramPacket);
                    lastPacketSent = lastPacketAcked + 1;
                }
            } catch (IOException e) {

            }

        }

        /**
         * Although multiple packets can be sent the specification does not say so.
         * So skipping.
         * Updates the @Field{lastTimeAckReceived} to present value of EPOCH time.
         */
        private void sendDataPacket( ){
            lastTimeAckReceived.set( System.currentTimeMillis());
//              for (int i = 1; i <= packetsToSend; i++) {
                    DatagramPacket datagramPacket = uploadBuffer.get(lastPacketAcked + 1);
                    try {
                        synchronized (socket) {
                            socket.send(datagramPacket);
                            slideQueueForward();
                            lastPacketSent = lastPacketAcked + 1;
                        }
                    } catch (IOException e) {

                    }
//                }

        }

        /**
         * Sends acknowledgement of the data packet. Or resends the same acknowledgement.
         * Depends on where it is invoked from.
         */
        private void sendAck() {

            lastTimeDataPacketReceived.set(System.currentTimeMillis());
            int ackNo = lastPacketRcvd;
            int magicNumber = Packet.MAGIC_NUMBER;
            short versionNumber = Packet.VERSION_NUMBER;
            short type = (short) Packet.PACKETTYPE.ACK.ordinal();
            int headerLength = Packet.HEADER_LENGTH;
            int sequence=0;
            Packet packet=new Packet(magicNumber,versionNumber,type,
                    headerLength,headerLength,sequence,ackNo,null);
            byte[] dataPayload= encodePacket(packet);
            try {
             DatagramPacket datagramPacket=new DatagramPacket(dataPayload,0,dataPayload.length,InetAddress.getByName(hostAddress),port);
             synchronized (socket){
                socket.send(datagramPacket);
            }
            } catch (IOException e) {

            }
        }


        private void incrementFailureCount() {
           failureCount.incrementAndGet();
        }

        /**
         * Removes packets that are acknowledged from the queue.
          */
        public void slideQueueForward() {
            ArrayList queue = uploadBuffer;
            for (int i = lastPacketAcked; i >=0; i--) {
                    if(queue.contains(i)){
                        queue.remove(i);
                    }
                    else{
                        break;
                    }

                }
            }




        public int getPeerId() {
            return peerId;
        }

        public void setPeerId(int peerId) {
            this.peerId = peerId;
        }

        public String getPeerAddress() {
            return peerAddress;
        }

        public void setPeerAddress(String peerAddress) {
            this.peerAddress = peerAddress;
        }

        public boolean isPeerAlive() {
            return isPeerAlive;
        }

        public void setPeerAlive(boolean isPeerAlive) {
            this.isPeerAlive = isPeerAlive;
        }

        public String getCurrentChunkId() {
            return currentChunkId;
        }

        public void setCurrentChunkId(String currentChunkId) {
            this.currentChunkId = currentChunkId;
        }

        public boolean isDownloading() {
            return isDownloading;
        }

        public void setDownloading(boolean isDownloading) {
            this.isDownloading = isDownloading;
        }

        public boolean isUploading() {
            return isUploading;
        }

        public void setUploading(boolean isUploading) {
            this.isUploading = isUploading;
        }


        public boolean isDownloadComplete() {
            return isDownloadComplete;
        }

        public void setDownloadComplete(boolean isDownloadComplete) {
            this.isDownloadComplete = isDownloadComplete;
        }

        public int getWindowSize() {
            return windowSize;
        }

        public void setWindowSize(int windowSize) {
            this.windowSize = windowSize;
        }


        public ArrayList<DatagramPacket> getUploadBuffer() {
            return uploadBuffer;
        }

        public void setUploadBuffer(ArrayList<DatagramPacket> uploadBuffer) {
            this.uploadBuffer = uploadBuffer;
        }




        public int getLastPacketAcked() {
            return lastPacketAcked;
        }

        public void setLastPacketAcked(int lastPacketAcked) {
            this.lastPacketAcked = lastPacketAcked;
        }

        public int getLastPacketSent() {
            return lastPacketSent;
        }

        public void setLastPacketSent(int lastPacketSent) {
            this.lastPacketSent = lastPacketSent;
        }

        public int getLastPacketAvailable() {
            return lastPacketAvailable;
        }

        public void setLastPacketAvailable(int lastPacketAvailable) {
            this.lastPacketAvailable = lastPacketAvailable;
        }

        public int getLastPacketRcvd() {
            return lastPacketRcvd;
        }

        public void setLastPacketRcvd(int lastPacketRcvd) {
            this.lastPacketRcvd = lastPacketRcvd;
        }

        public int getNextPacketExpected() {
            return nextPacketExpected;
        }

        public void setNextPacketExpected(int nextPacketExpected) {
            this.nextPacketExpected = nextPacketExpected;
        }

        /**
         *  To ensure that we acknowledge the correct packet
         *  Handles reordering of packets.
          * @return the last packet in sequence
         */
        public Integer getLastContiguousSequencePacket() {
            ArrayList list=getDataBuffer();
            if(list==null||list.size()==0)
            {  return -1;
            }
            else
            {
                int i;
                for( i=0;i<list.size();i++){
                   if(list.get(i)==null){
                       return i-1;
                   }
                }
                return i-1;


            }

        }
    }

    /**
     * Just a class to for tracking and removing invalid nodes
     */
    private static class NodeChunkDate implements Serializable {
        private final AtomicLong nodeStartDate= new AtomicLong(System.currentTimeMillis());
        private final String chunkId;

       public NodeChunkDate(String chunkId){

            this.chunkId=chunkId;
        }

        public AtomicLong getNodeStartDate() {
            return nodeStartDate;
        }

        public String getChunkId() {
            return chunkId;
        }
    }
}
