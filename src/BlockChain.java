/*--------------------------------------------------------

1. Jilei Hao / Feb 27, 2019

2. JDK 1.8

3. Command line instructions

> javac *.java
> java BlockChain 0

separate window:
> java BlockChain 1

separate window
> java BlockChain 2



4. Instructions to run this program:

> Use Master script to run all processes

5. Note:
Program occasionally run into NumberFormat exceptions. If getting exceptions during one run. Please give another try.

References:
http://condor.depaul.edu/elliott/435/hw/programs/Blockchain/program-block.html

----------------------------------------------------------*/


import javax.crypto.Cipher;
import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Marshaller;
import javax.xml.bind.Unmarshaller;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlRootElement;
import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.PriorityBlockingQueue;

/*-------------------------------------------
    User Interface and Control
---------------------------------------------*/
// main() entry, controller of the entire processing and user interface
public class BlockChain {
    static int ProcessID = 0;
    static int TotalProcess = 3;
    static String ServerName = "localhost";
    static private KeyPair keyPair;

    public static void main(String[] args) {
        // Processing Arguments
        if (args.length > 0)
            BlockChain.ProcessID = Integer.parseInt(args[0]);

        System.out.println("Starting process: " + ProcessID);


        // Process 2 starting logic
        if (BlockChain.ProcessID != 2){
            int ql = 6;
            int localPort = Ports.GetPort(BlockChain.ProcessID, PortType.UnverifiedBlockPort);
            try{
                Socket s;
                ServerSocket ss = new ServerSocket(localPort, ql);
                System.out.println("Process Paused. Waiting Process 2 to start...");
                s = ss.accept();
                System.out.println("Start signal received, resuming process...");
                s.close();
                ss.close();
            } catch (IOException x){
                x.printStackTrace();
            }
        }
        else{
            try{Thread.sleep(1000);}catch(Exception x){x.printStackTrace();} // Make sure all servers are up
            ToolBox.BroadCast("Start", PortType.UnverifiedBlockPort);
        }

        // Initialization
        UnverifiedQueue pUVQueue = new UnverifiedQueue();
        PublicKeyRepo pKeyRepo = new PublicKeyRepo();
        BlockChainRepo bcRepo = BlockChainRepo.getInstance();
        keyPair = ToolBox.generateKeyPair(1000 + BlockChain.ProcessID); // using 1000 for process 0, 1001 for process 1, etc.


        // Starting PublicKeyServer
        new Thread(new PublicKeyServer(pKeyRepo)).start();
        try{Thread.sleep(1000);}catch(Exception x){x.printStackTrace();} // Make sure all servers are up

        // Add key to Repo
        PublicKeyWrapper kw = new PublicKeyWrapper(BlockChain.ProcessID, keyPair.getPublic());
        pKeyRepo.add(BlockChain.ProcessID, kw);

        // BroadCast Key to Other processes
        ToolBox.BroadCast(ToolBox.getMarshaledString(kw, false), PortType.KeyPort);

        // Starting UnverifiedBlockServer
        new Thread(new UnverifiedBlockServer(pUVQueue)).start();
        try{Thread.sleep(1000);}catch(Exception x){x.printStackTrace();} // Make sure all servers are up

	    // Load local data file to local UV queue
        String localFileName = "BlockInput" + ProcessID + ".txt";
	    // Load file and multicast loaded records to other processes
        pUVQueue.LoadFromFile(localFileName);

        // Start Verification Server
        try{Thread.sleep(2000);}catch(Exception x){x.printStackTrace();} // Make sure all servers are up
        new Thread(new BlockChainVerificationServer()).start();


    }
}

/*-------------------------------------------
    Unverified Block Components
---------------------------------------------*/
// Unverified block server that run worker to receive unverified blocks from other processes
class UnverifiedBlockServer implements Runnable{
    UnverifiedQueue UVQueue;
    int localPort;

    // Constructor getting PQ reference from main thread
    UnverifiedBlockServer(UnverifiedQueue q){
        this.UVQueue = q;
        localPort = Ports.GetPort(BlockChain.ProcessID, PortType.UnverifiedBlockPort);
    }

    @Override
    public void run() {
        int ql = 6;
        Socket s;
        try{
            ServerSocket ss = new ServerSocket(localPort, ql);
            System.out.println("Unverified Block Server Started. Process: "
                    + BlockChain.ProcessID + " Port: " + localPort);
            while (true){
                // Get new unverified block
                s = ss.accept();
                // Start the worker to process the block
                new UnverifiedBlockWorker(s, UVQueue).start();
            }
        } catch (IOException x){
            x.printStackTrace();
        }
    }
}

class UnverifiedBlockWorker extends Thread {
    Socket s;
    UnverifiedQueue uvQ;

    UnverifiedBlockWorker (Socket _s, UnverifiedQueue q){
        this.s = _s;
        this.uvQ = q;
    }

    @Override
    public void run(){
        try{
            BufferedReader r = new BufferedReader(new InputStreamReader(s.getInputStream()));
            String oneLine = r.readLine();
            uvQ.add((BlockRecord)ToolBox.getUnmarshalledObject(oneLine, BlockRecord.class));
            System.out.println("Unverified Block worker: Data Enqueued.");

            //System.out.println("Debug: Current Queue Size: " + uvQ.getQueue().size());

        } catch (IOException x){
            x.printStackTrace();
        }
    }
}

// Storage of Unverified Block, contains actual data in priority Queue
class UnverifiedQueue {
    private static BlockingQueue<BlockRecord> privUnverifiedQueue
            = new PriorityBlockingQueue<>(20, new BlockRecordComparator());
    private static UnverifiedQueue privInstance = new UnverifiedQueue();

    // constructor
    UnverifiedQueue(){ }

    // Singleton method
    static UnverifiedQueue getInstance() {
        return privInstance;
    }

    // public accessor of the queue
    public BlockingQueue<BlockRecord> getQueue(){
        return privUnverifiedQueue;
    }

    // method adding data to the queue
    public void add (BlockRecord br) {
        privUnverifiedQueue.add(br);
    }

    // method retrieving data
    public BlockRecord poll () {
        return privUnverifiedQueue.poll();
    }

    // Load the queue from local data file
    // Broadcast after successfully loading each record
    public void LoadFromFile (String FilePath) {
        File f = new File (FilePath);

        System.out.println("Loading local data from file: " + FilePath);

        try{
            BufferedReader r = new BufferedReader(new FileReader(f));
            String oneLine;
            String [] tmpResult;
            // Read file line by line to parse the record
            while ((oneLine = r.readLine()) != null) {
                //System.out.println(oneLine);
                tmpResult = oneLine.split(" ");

                // Create a new BlockRecord
                BlockRecord uvb = new BlockRecord(tmpResult);
                this.privUnverifiedQueue.add(uvb);

                // Broadcast to all participating nodes
                ToolBox.BroadCast(ToolBox.getMarshaledString(uvb, false), PortType.UnverifiedBlockPort);

                // For generating different timestamp
                try {
                    Thread.sleep(1);
                } catch (java.lang.InterruptedException x){
                    x.printStackTrace();
                }
            }
            System.out.println("File loading completed.");

        } catch (IOException x){
            x.printStackTrace();
        }

        /* Debug: CHeck if the priority queue has been loaded correctly
        BlockRecord tmp = this.privUnverifiedQueue.poll();
        while (tmp != null){
            System.out.println("Debug (Remove this after done): " + tmp.TimeStamp);
            tmp = this.privUnverifiedQueue.poll();
        }
        */
    }

}



// Model of Unverified Block
@XmlRootElement
class BlockRecord {

    // Data to be Hashed begin
    String BlockNum = ""; // Sequential number 1 greater than the last V block. Will be changed during work.
    String VerificationProcessID = ""; // indicating the process verifying the UV block. Will be changed during work.
    String CreatingProcessID; // indicating the process created the UV Block
    String BlockID; // UUID uniquely identifying each UV block
    String SignedBlockID; // Signed by creating process
    String TimeStamp; // Time unverified block generated
    String FName;
    String LName;
    String SSNum;
    String DOB;
    String Diagnosis;
    String Treatment;
    String Rx;
    String Notes;
    // end of Data to be Hashed

    // For Verification Use
    String SHA256String = ""; // Hash value of the data
    String Signed256String = ""; // Signed Data Hash Result
    String DataHash = ""; // Optional feature
    String PreviousHash = ""; // Previous Hash String
    String Seed = ""; // 256 bit Seed string for verification


    // Subset of fields for hash
    public BlockRecordForHash getBRForHash(){
        BlockRecordForHash ret = new BlockRecordForHash();

        ret.setBlockNum(this.BlockNum);
        ret.setVerificationProcessID(this.VerificationProcessID);
        ret.setCreatingProcessID(this.CreatingProcessID);
        ret.setBlockID(this.BlockID);
        ret.setSignedBlockID(this.SignedBlockID);
        ret.setTimeStamp(this.TimeStamp);
        ret.setFName(this.FName);
        ret.setLName(this.LName);
        ret.setSSNum(this.SSNum);
        ret.setDOB(this.DOB);
        ret.setDiagnosis(this.Diagnosis);
        ret.setTreatment(this.Treatment);
        ret.setRx(this.Rx);
        ret.setNotes(this.Notes);
        ret.setDataHash(this.DataHash);
        ret.setPreviousHash(this.PreviousHash);
        ret.setSeed(this.Seed);

        return ret;
    }

    // Default constructor
    public BlockRecord(){}

    // Specialized constructor for loading from local file
    public BlockRecord(String[] raw){
        // Populating fields
        this.FName = raw[0];
        this.LName = raw[1];
        this.DOB = raw[2];
        this.SSNum = raw[3];
        this.Diagnosis = raw[4];
        this.Treatment = raw[5];
        this.Rx = raw[6];

        // Generating UUID for the block
        this.BlockID = UUID.randomUUID().toString();
        //System.out.println("BlockID: " + this.BlockID.toString());

        // Populate CreatingProcessID
        this.CreatingProcessID = Integer.toString(BlockChain.ProcessID);

        // Generating timestamp
        SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd.HH:mm:ss.SSS");
        Date date = new Date();
        String T1 = df.format(date);

        // --Adding process number to avoid collision
        String TimeStampString = T1 + "." + BlockChain.ProcessID;
        this.TimeStamp = TimeStampString;
        //System.out.println("Timestamp: " + TimeStampString);
    }

    public static BlockRecord getDummyBlock(){
        BlockRecord ret = new BlockRecord();
        ret.setBlockID("FirstDummyBlock");
        ret.setCreatingProcessID("0");
        ret.setTimeStamp("1900-01-01.00:00:00.000.0");
        ret.setBlockNum("0");
        ret.setFName("First");
        ret.setVerificationProcessID("0");

        ret.setLName("Block");
        ret.setDOB("1900-01-01");
        ret.setSSNum("000-000-0000");
        ret.setDiagnosis("Null");
        ret.setTreatment("SelfDestruction");
        ret.setRx("GarbageCollector");

        BlockRecordForHash brh = ret.getBRForHash();
        ret.setSHA256String(ToolBox.ByteToHex(ToolBox.getSHA256Hash(ToolBox.getMarshaledString(brh, false))));

        return ret;
    }

    // XML Accessors
    public String getSigned256String() {return Signed256String;}
    @XmlElement
    public void setSigned256String(String x) {this.Signed256String = x;}

    public String getSHA256String() {return SHA256String;}
    @XmlElement
    public void setSHA256String(String x) {this.SHA256String = x;}

    public String getVerificationProcessID() {return VerificationProcessID;}
    @XmlElement
    public void setVerificationProcessID(String x) {this.VerificationProcessID = x;}

    public String getPreviousHash() {return PreviousHash;}
    @XmlElement
    public void setPreviousHash(String x) {this.PreviousHash = x;}

    public String getSeed() {return Seed;}
    @XmlElement
    public void setSeed(String x) {this.Seed = x;}

    public String getBlockNum() {return BlockNum;}
    @XmlElement
    public void setBlockNum(String x) {this.BlockNum = x;}

    public String getBlockID() {return this.BlockID;}
    @XmlElement
    public void setBlockID(String x) {this.BlockID = x;}

    public String getSignedBlockID() {return SignedBlockID;}
    @XmlElement
    public void setSignedBlockID(String x) {this.SignedBlockID = x;}

    public String getCreatingProcessID() {return CreatingProcessID;}
    @XmlElement
    public void setCreatingProcessID(String x) {this.CreatingProcessID = x;}

    public String getDataHash() {return this.DataHash;}
    @XmlElement
    public void setDataHash(String x) {this.DataHash = x;}

    public String getTimeStamp() {return this.TimeStamp;}
    @XmlElement
    public void setTimeStamp(String x) {this.TimeStamp = x;}

    public String getFName() {return this.FName;}
    @XmlElement
    public void setFName(String x) {this.FName = x;}

    public String getLName() {return this.LName;}
    @XmlElement
    public void setLName(String x) {this.LName = x;}

    public String getDOB() {return this.DOB;}
    @XmlElement
    public void setDOB(String x) {this.DOB = x;}

    public String getSSNum() {return this.SSNum;}
    @XmlElement
    public void setSSNum(String x) {this.SSNum = x;}

    public String getDiagnosis() {return this.Diagnosis;}
    @XmlElement
    public void setDiagnosis(String x) {this.Diagnosis = x;}

    public String getTreatment() {return this.Treatment;}
    @XmlElement
    public void setTreatment(String x) {this.Treatment = x;}

    public String getRx() {return this.Rx;}
    @XmlElement
    public void setRx(String x) {this.Rx = x;}

    public String getNotes() {return this.Notes;}
    @XmlElement
    public void setNotes(String x) {this.Notes = x;}

}

// Extracted fields from BlockRecord for hash
@XmlRootElement
class BlockRecordForHash{
    String BlockNum = ""; // Sequential number 1 greater than the last V block. Will be changed during work.
    String VerificationProcessID = ""; // indicating the process verifying the UV block. Will be changed during work.
    String CreatingProcessID; // indicating the process created the UV Block
    String BlockID; // UUID uniquely identifying each UV block
    String SignedBlockID; // Signed by creating process
    String TimeStamp; // Time unverified block generated
    String FName;
    String LName;
    String SSNum;
    String DOB;
    String Diagnosis;
    String Treatment;
    String Rx;
    String Notes;
    String DataHash = ""; // Optional feature
    String PreviousHash = ""; // Previous Hash String
    String Seed = ""; // 256 bit Seed string for verification

    public BlockRecordForHash(){}

    // XML Accessors
    public String getVerificationProcessID() {return VerificationProcessID;}
    @XmlElement
    public void setVerificationProcessID(String x) {this.VerificationProcessID = x;}

    public String getPreviousHash() {return PreviousHash;}
    @XmlElement
    public void setPreviousHash(String x) {this.PreviousHash = x;}

    public String getSeed() {return Seed;}
    @XmlElement
    public void setSeed(String x) {this.Seed = x;}

    public String getBlockNum() {return BlockNum;}
    @XmlElement
    public void setBlockNum(String x) {this.BlockNum = x;}

    public String getBlockID() {return this.BlockID;}
    @XmlElement
    public void setBlockID(String x) {this.BlockID = x;}

    public String getSignedBlockID() {return SignedBlockID;}
    @XmlElement
    public void setSignedBlockID(String x) {this.SignedBlockID = x;}

    public String getCreatingProcessID() {return CreatingProcessID;}
    @XmlElement
    public void setCreatingProcessID(String x) {this.CreatingProcessID = x;}

    public String getDataHash() {return this.DataHash;}
    @XmlElement
    public void setDataHash(String x) {this.DataHash = x;}

    public String getTimeStamp() {return this.TimeStamp;}
    @XmlElement
    public void setTimeStamp(String x) {this.TimeStamp = x;}

    public String getFName() {return this.FName;}
    @XmlElement
    public void setFName(String x) {this.FName = x;}

    public String getLName() {return this.LName;}
    @XmlElement
    public void setLName(String x) {this.LName = x;}

    public String getDOB() {return this.DOB;}
    @XmlElement
    public void setDOB(String x) {this.DOB = x;}

    public String getSSNum() {return this.SSNum;}
    @XmlElement
    public void setSSNum(String x) {this.SSNum = x;}

    public String getDiagnosis() {return this.Diagnosis;}
    @XmlElement
    public void setDiagnosis(String x) {this.Diagnosis = x;}

    public String getTreatment() {return this.Treatment;}
    @XmlElement
    public void setTreatment(String x) {this.Treatment = x;}

    public String getRx() {return this.Rx;}
    @XmlElement
    public void setRx(String x) {this.Rx = x;}

    public String getNotes() {return this.Notes;}
    @XmlElement
    public void setNotes(String x) {this.Notes = x;}

}

// Comparator for Priority Queue sorting BlockRecords
class BlockRecordComparator implements Comparator<BlockRecord> {
    @Override
    public int compare(BlockRecord o1, BlockRecord o2) {
        int ret;

        ret = Long.compare(GetNumber(o1, false), GetNumber(o2, false));

        // if data time are equal, compare process ID
        if (ret == 0)
            ret = Long.compare(GetNumber(o1, true), GetNumber(o2, true));

        return ret;
    }

    long GetNumber(BlockRecord b, boolean isProcess){
        return (isProcess ?
                Long.parseLong(b.TimeStamp.substring(25)
                        .replace("\r", "")
                        .replace("\n", "")) :
                Long.parseLong(b.TimeStamp.substring(0,24)
                        .replace("-", "")
                        .replace(".", "")
                        .replace(":", "")
                        .trim()));
    }
}

/*-------------------------------------------
    Public Key Components
---------------------------------------------*/

class PublicKeyServer implements Runnable {
    int localPort;
    PublicKeyRepo pkRepo;

    public PublicKeyServer(PublicKeyRepo repo){
        this.localPort = Ports.GetPort(BlockChain.ProcessID, PortType.KeyPort);
        this.pkRepo = repo;
    }

    @Override
    public void run(){
        int ql = 6;
        Socket s;
        try{
            ServerSocket ss = new ServerSocket(localPort, ql);
            System.out.println("Public Key Server Started. Process: "
                    + BlockChain.ProcessID + " Port: " + localPort);
            while (true){
                // Get Public Key from other process
                s = ss.accept();
                // Start the worker to process the key
                new PublicKeyWorker(s, pkRepo).start();
            }
        } catch (IOException x){
            x.printStackTrace();
        }
    }
}

class PublicKeyRepo {
    Map<Integer, PublicKeyWrapper> keyMap;

    public PublicKeyRepo (){
        this.keyMap = new HashMap<>();
    }

    public void add(int Process, PublicKeyWrapper keyWrapper){
        this.keyMap.put(Process, keyWrapper);
    }

    public PublicKeyWrapper getKeyByProcess(int Process){
        return this.keyMap.get(Process);
    }
}

@XmlRootElement
class PublicKeyWrapper {
    String ProcessID;
    String KeyString;
    PublicKey Key;
    byte[] KeyBytes;

    // Default Constructor for marshalling
    public PublicKeyWrapper(){}

    // Constructor
    public PublicKeyWrapper(int ProcessID, PublicKey k){
        this.ProcessID = Integer.toString(ProcessID);
        this.Key = k;
        this.KeyBytes = k.getEncoded();
        this.KeyString = ToolBox.ByteToHex(this.KeyBytes);
    }

    public String getProcessID(){ return this.ProcessID;}
    @XmlElement
    public void setProcessID(String x) {this.ProcessID = x;}

    public String getKeyString(){ return this.KeyString;}
    @XmlElement
    public void setKeyString(String x) {this.KeyString = x;}

    public String getMarshaledString (){
        StringWriter sw = new StringWriter();

        try{
            JAXBContext jaxbContext = JAXBContext.newInstance(PublicKeyWrapper.class);
            Marshaller jaxbMarshaller = jaxbContext.createMarshaller();


            jaxbMarshaller.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, true);

            jaxbMarshaller.marshal(this, sw);

        } catch(JAXBException x){
            x.printStackTrace();
        }

        return sw.toString();
    }
}

class PublicKeyWorker extends Thread{
    Socket s;
    PublicKeyRepo pkRepo;

    PublicKeyWorker(Socket _s, PublicKeyRepo repo){
        this.s = _s;
        this.pkRepo = repo;
    }

    @Override
    public void run(){
        try{
            BufferedReader r = new BufferedReader(new InputStreamReader(s.getInputStream()));
            String oneLine = r.readLine();
            PublicKeyWrapper kw = (PublicKeyWrapper)ToolBox.getUnmarshalledObject(oneLine, PublicKeyWrapper.class);
            pkRepo.add(Integer.parseInt(kw.getProcessID()), kw);
            System.out.println("Public Key Worker: Key received.");

            //System.out.println("Debug: Current Queue Size: " + uvQ.getQueue().size());

        } catch (IOException x){
            x.printStackTrace();
        }
    }
}

/*-------------------------------------------
    BlockChain Verification Component
---------------------------------------------*/

// Listening to newly verified block
// Once got the block, Start new thread working on the next;
class BlockChainVerificationServer implements Runnable{
    int localPort;


    public BlockChainVerificationServer(){
        this.localPort = Ports.GetPort(BlockChain.ProcessID, PortType.VerifiedBlockPort);
    }

    @Override
    public void run(){
        int ql = 6;
        Socket s;
        try{
            ServerSocket ss = new ServerSocket(localPort, ql);
            System.out.println("BlockChain Verification Server Started. Process: "
                    + BlockChain.ProcessID + " Port: " + localPort);


            // Insert the first dummy block
            BlockRecord dummy = BlockRecord.getDummyBlock();
            BlockChainRepo.add(dummy);
            System.out.println("Dummy Block Added");


            // Kick off worker for first block immediately
            new BlockChainVerificationWorker().start();

            while (true){
                // Get Public Key from other process
                s = ss.accept();
                // Start a update worker
                new BlockChainUpdateWorker(s).start();
            }
        } catch (IOException x){
            x.printStackTrace();
        }
    }
}

// Local Storage of the verified blockChain
class BlockChainRepo {
    static LinkedList<BlockRecord> blockList = new LinkedList<>();
    private static BlockChainRepo privInsatance = new BlockChainRepo();
    static BlockRecord pLatest;

    public BlockChainRepo () {}

    public static BlockChainRepo getInstance() {
        return privInsatance;
    }

    public static void add(BlockRecord br){
        // Put a instance to the HashMap
        // Build the linked list
        pLatest = br;
        blockList.add(br);
    }

    // Return the most recent block
    public static BlockRecord getLatest() {
        return pLatest;
    }

    // Check if latest block has given ID
    public static boolean isVerified(String ID){
        if (pLatest == null){
            return false;
        }


        if (pLatest.getBlockID().equals(ID))
            return true;
        else
            return false;
    }

    public static void GenerateLedger(){
        if (pLatest == null)
            return;

        StringBuilder sb = new StringBuilder();
        BlockRecord crnt = blockList.poll();
        String m;

        while(crnt != null){
            m = ToolBox.getMarshaledString(crnt, true);
            sb.append(m.substring(m.indexOf("<blockRecord>")));
            crnt = blockList.poll();
        }

        File f = new File("BlockchainLedger.xml");

        // Delete existing file
        if (f.exists()){
            f.delete();
        }

        // Create new file
        try{ f.createNewFile(); } catch (IOException x){ x.printStackTrace(); }

        try{
            // Open a new writer
            BufferedWriter fWriter = new BufferedWriter(new FileWriter("BlockchainLedger.xml", true));

            // Write content to file
            fWriter.write(sb.toString());

            fWriter.close();

        } catch(IOException x){
            x.printStackTrace();
        }



    }
}

// Unmarshall the blockchain received and put into the Repo
class BlockChainUpdateWorker extends Thread{
    Socket s;

    public BlockChainUpdateWorker(Socket _s){
        this.s = _s;
    }

    @Override
    public void run(){
        try{
            BufferedReader r = new BufferedReader(new InputStreamReader(s.getInputStream()));
            String oneLine = r.readLine();
            BlockRecord br = (BlockRecord)ToolBox.getUnmarshalledObject(oneLine, BlockRecord.class);
            BlockChainRepo.add(br);
            System.out.println("BlockChain update worker: Verified Block Received: " + br.getBlockID());
        } catch (Exception x){
            x.printStackTrace();
        }
    }
}

// Pop the UVBlock from the PQ and Start the work
class BlockChainVerificationWorker extends Thread{
    BlockChainVerificationWorker(){}

    @Override
    public void run(){
        BlockRecord crnt = UnverifiedQueue.getInstance().poll();


        while(crnt != null){
            System.out.println("Processing Block: " + crnt.getBlockID());

            //Work Start
            BlockRecord prev = BlockChainRepo.getLatest();

            //Populating Fields
            // -- (1/5) Verifying Process ID
            crnt.setVerificationProcessID(Integer.toString(BlockChain.ProcessID));
            // -- (2/5) BlockNum
            crnt.setBlockNum(Integer.toString(Integer.parseInt(prev.BlockNum)));
            // -- (3/5) New Data Hash
            crnt.setDataHash(ToolBox.ByteToHex(ToolBox.getSHA256Hash(ToolBox.getMarshaledString(crnt.getBRForHash(), false))));
            // -- (4/5) Previous Hash
            crnt.setPreviousHash(prev.SHA256String);

            // -- (5/5) Seed
            String seed = ToolBox.randomString(8);
            crnt.setSeed(seed);

            // Check if block has been verified
            while (!BlockChainRepo.isVerified(crnt.BlockID)){
                System.out.println("Trying seed: " + seed);

                // Calculate Hash for entire block
                String newHash = ToolBox.ByteToHex(ToolBox.getSHA256Hash(ToolBox.getMarshaledString(crnt, false)));

                // Examine if hash is good
                if (Integer.parseInt(newHash.substring(0,4), 16) < 2500){
                    System.out.println("Puzzle solved! Block: " + ToolBox.getMarshaledString(crnt, false));
                    // Check again
                    if (!BlockChainRepo.isVerified(crnt.BlockID))
                        ToolBox.BroadCast(ToolBox.getMarshaledString(crnt, false), PortType.VerifiedBlockPort);
                    else
                        System.out.println("But other process has verified first. Discarding result.");

                    break;
                }

                seed = ToolBox.randomString(8);
                crnt.setSeed(seed);

                // Work really hard!
                try{Thread.sleep(500);}catch(Exception x){x.printStackTrace();}
            }

            // Wait for Block chain update to settle
            try{Thread.sleep(2000);}catch(Exception x){x.printStackTrace();}
            crnt = UnverifiedQueue.getInstance().poll();
        }

        try{Thread.sleep(1000);}catch(Exception x){x.printStackTrace();}
        System.out.println("Jobs done!");

        // Process 0 export the ledger file
        if (BlockChain.ProcessID == 0){
            BlockChainRepo.GenerateLedger();
        }
    }
}

/*-------------------------------------------
    Utilities
---------------------------------------------*/

class Ports {
    static int KeyPortBase = 4710;
    static int UnverifiedBlockPortBase = 4820;
    static int VerifiedBlockPortBase = 4930;



    public static int GetPort (int processID, PortType portType){
        if (portType == PortType.KeyPort)
            return KeyPortBase + processID;
        else if (portType == PortType.UnverifiedBlockPort)
            return UnverifiedBlockPortBase + processID;
        else    // VerifiedBlockPort
            return VerifiedBlockPortBase + processID;
    }
}

enum PortType {
    KeyPort, UnverifiedBlockPort, VerifiedBlockPort
}

class ToolBox {
    // method generating new keypair
    public static KeyPair generateKeyPair(long seed){
        KeyPair ret = null;
        try{
            KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA");
            SecureRandom rng = SecureRandom.getInstance("SHA1PRNG", "SUN");
            rng.setSeed(seed);
            keyGen.initialize(1024, rng);
            ret = keyGen.generateKeyPair();
        } catch (Exception x){
            x.printStackTrace();
        }

        return ret;
    }


    // method marshalling object
    public static String getMarshaledString (Object o, boolean isFormated){
        StringWriter sw = new StringWriter();

        try{
            JAXBContext jaxbContext = JAXBContext.newInstance(o.getClass());
            Marshaller jaxbMarshaller = jaxbContext.createMarshaller();

            if (isFormated == true)
                jaxbMarshaller.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, true);

            jaxbMarshaller.marshal(o, sw);

        } catch(JAXBException x){
            x.printStackTrace();
        }

        return sw.toString();
    }

    // method unmarshalling object
    public static Object getUnmarshalledObject (String str, Class targetClass){
        Object ret = null;

        try{
            JAXBContext jaxbContext = JAXBContext.newInstance(targetClass);
            Unmarshaller jaxbUnmarshaller = jaxbContext.createUnmarshaller();
            StringReader r = new StringReader(str);
            ret = jaxbUnmarshaller.unmarshal(r);
        }catch(JAXBException x){
            x.printStackTrace();
        }

        return ret;
    }

    // method braodcast data to nodes
    public static void BroadCast (String xmlString, PortType pType){
        Socket s;
        PrintStream outStream;

        try{
            for (int i = 0; i < BlockChain.TotalProcess; i++){
                // no need to broadcast to itself, unless it's verified block
                if (i == BlockChain.ProcessID && pType != PortType.VerifiedBlockPort)
                    continue;

                // Connect to given process and send the xmlString
                s = new Socket(BlockChain.ServerName, Ports.GetPort(i, pType));
                outStream = new PrintStream(s.getOutputStream());
                outStream.println(xmlString);
                outStream.flush();
                s.close();
            }
        } catch (IOException x){
            x.printStackTrace();
        }
    }

    // method generating SHA256 encoded string
    public static byte[] getSHA256Hash(String _in){
        byte[] digestedData = null;

        // Digest input
        try{
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(_in.getBytes());
            digestedData = md.digest();
        }catch(Exception x){
            x.printStackTrace();
        }

        return digestedData;
    }

    // method converting byte[] to string
    public static String ByteToHex(byte[] _in){
        StringBuilder sb = new StringBuilder();

        // UNVERIFIED CODE from Utility.java
        for (int i = 0; i < _in.length; i++) {
            sb.append(Integer.toString((_in[i] & 0xff) + 0x100, 16).substring(1));
        }

        return sb.toString();
    }

    // method generating random string
    public static String randomString(int cnt){
        StringBuilder sb = new StringBuilder();
        String ALPHA_NUMERIC_STRING = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";

        while (cnt-- != 0){
            int character = (int)(Math.random()*ALPHA_NUMERIC_STRING.length());
            sb.append(ALPHA_NUMERIC_STRING.charAt(character));
        }
        return sb.toString();
    }
}









