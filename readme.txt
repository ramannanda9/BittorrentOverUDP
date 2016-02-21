**************README*************
1. For the detailed design specification read the document. 
2. Entire source code has been written by me over the course of week. (Pretty hectic week) 
3. The main class of the code is SetupPeer.java. This is the entry point. Rest of the specification is copied from the document itself.

Source code files and their description.

•	Peer (Peer.java):   This is the class that is designed to handle functionality for managing all the requests i.e. ACK, GET, DATA, WHOHAS, IHAVE and DENY. In this class in addition to ensuring reliability for DATA requests, I have implemented reliability for GET requests, handled timeouts for both uploading and downloading peer. This implementation ensures parallel downloads from multiple peers at the same time whilst ensuring restriction that a peer can only download or upload to a single peer, but may download and upload to other peers simultaneously. This class has a listener thread, which listens for incoming packet requests, this listener thread discards the packet, if the packets version and magic number do not match, 1 and 15441 respectively. The code in this class can handle highly concurrent requests without synchronization overhead as it uses Java’s concurrency packages such as AtomicLong, ConcurrentHashMap, CopyOnWriteArrayList, etc.
      
•	ReliabilityAndCongestionHandler: This is a nested class within Peer.java, this manages upload and download of data packets from a Peer. An instance is created for an Active peer upload and download, it manages reliability and congestion for upload and download of the data packets. It also removes un-responding peers whether they are uploading or downloading, this ensures that it can serve other requests and not block or wait indefinitely on bad peers. This functionality of removing bad peers is accomplished by using a daemon thread which checks whether an ACK or DATA packet has been received within the last 5 seconds and then handling the corresponding case accordingly.

•	Packet (Packet.java): This is just a java bean class that stores the packet information. As in java the unsigned data types are not supported, so the packet header here is of 24 bytes, similarly chunk hash size is of 40 bytes. Also as java does not support raw sockets, so the header information is encoded within the data portion of the payload. This leaves 1476 bytes for the data.

•	FileHelper (FileHelper.java): This class is used for handling all file related portions including reading chunks of data from master data file, writing output chunk and peer-problem files to temporary directory.  

•	SetupPeer (SetupPeer.java): This is the entry point for the project, it handles user input, prints out help message for command usage on the command line, initiates a peer instance, and starts monitor thread of peer. 
