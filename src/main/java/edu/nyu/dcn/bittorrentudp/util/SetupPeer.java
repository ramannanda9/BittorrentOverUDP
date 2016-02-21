package edu.nyu.dcn.bittorrentudp.util;


import edu.nyu.dcn.bittorrentudp.core.Peer;
import org.apache.commons.cli.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


import java.util.NoSuchElementException;
import java.util.Scanner;

/**
 * Created by Ramandeep Singh on 16-12-2015.
 */
public class SetupPeer {

    private static final Logger logger = LoggerFactory.getLogger(SetupPeer.class);
    public static  int debugLevel=0;

    public static void main(String args[]) {

        Options options=addOptions();
        CommandLineParser parser=new DefaultParser();
        CommandLine cmd= null;
        String peerFile="";
        String chunkFile="";
        int maximumConnections=512;
        String masterChunkFile="";
        int peerIdentity=0;
        try {
            cmd = parser.parse(options, args);
            peerFile=cmd.getOptionValue("p");
            isNullOrEmpty(peerFile);
            chunkFile=cmd.getOptionValue("c");
            isNullOrEmpty(chunkFile);
            if(cmd.hasOption("m")){
                if(cmd.getOptionValue("m")!=null){
                    maximumConnections=Integer.parseInt(cmd.getOptionValue("m"));
                }
            }
            masterChunkFile=cmd.getOptionValue("f");
            isNullOrEmpty(masterChunkFile);

            isNullOrEmpty(cmd.getOptionValue("i"));
            peerIdentity=Integer.parseInt(cmd.getOptionValue("i"));

            if(cmd.hasOption("d")){
                isNullOrEmpty("d");
                debugLevel=Integer.parseInt(cmd.getOptionValue("d"));

            }
        } catch (ParseException e) {
           HelpFormatter formatter=new HelpFormatter();
           formatter.printHelp(" java -jar peer.jar ",options);
            return;
        }

        Peer peer=new Peer(peerFile,chunkFile,maximumConnections,masterChunkFile,peerIdentity);
        peer.startListeningForResponse();
        Scanner scanner=new Scanner(System.in);
        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            public void run() {
                FileHelper.closeFile();
            }
        }));
        try{
        while(true){

            String userCommand=scanner.nextLine();
            if(userCommand!=null&&!userCommand.isEmpty()) {
                String[] array = userCommand.split("\\s+");
                peer.handleGetRequestFromClient(array[1], array[2]);
                try {
                    Thread.sleep(300);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }
        }catch (NoSuchElementException e){

        }






    }

    /**
     * Checks for null or empty
     * @return
     */
    public static boolean isNullOrEmpty(String value) throws ParseException {
        if(value==null||value.isEmpty()){
            throw new ParseException("Value is required");
        }
       return false;
    }

    /**
     * The program expects to be specified
     * -p the peers file that contains the list of peers
     * -c the chunk file for the peer
     * -m the maximum number of connections that peer can have
     * -f the master chunk file.
     * -i the identity
     * -d the debug flag
     */
    private static Options addOptions() {
        Options options= new Options();
        options.addOption("p",true,"The peers file that contains the list of peers");
        options.addOption("c",true,"The chunk file for the peer");
        options.addOption("m",true,"The maximum number of connections that peer can have");
        options.addOption("f",true,"The master chunk file");
        options.addOption("i",true,"The identity of the peer");
        options.addOption("d",true,"The debug level \n 0: No Debug \n 1: Errors \n " +
                "2: Initialization \n 3: Sockets \n 4: Processes \n 5: Spiffy ");
        return options;

    }
}
