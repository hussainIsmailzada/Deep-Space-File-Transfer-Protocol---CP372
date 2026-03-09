import java.io.*;
import java.net.*;
import java.util.Arrays;

public class Receiver {

    // state
    private DatagramSocket socket;
    private InetAddress  senderAddr;
    private int senderPort;
    private int ackCount = 0;   /
    private int rn;            
    private FileOutputStream fos;          /

    // constructors
    public Receiver(int port, int rn, String outputFile) throws Exception {
        this.socket = new DatagramSocket(port); 
        this.socket.setSoTimeout(0); // Infinite timeout waits patiently for Sender
        this.rn = rn;
        this.fos = new FileOutputStream(outputFile);
        System.out.println("[Receiver] Listening on port " + port);
    }

    // ── helpers

    /** Block until proper datagram arrives*/
    private DSPacket receivePacket() throws Exception {
        byte[]         buf = new byte[DSPacket.MAX_PACKET_SIZE];
        DatagramPacket udp = new DatagramPacket(buf, buf.length);
        socket.receive(udp);

        // Dynamically capture the actual port the untouched Sender.java is using
        this.senderAddr = udp.getAddress();
        this.senderPort = udp.getPort();

        return new DSPacket(udp.getData());
    }

    /** Send an ACK back to the sender*/
    private void sendACK(int seqNum) throws Exception {
        ackCount++;  // 1-indexed

        if (ChaosEngine.shouldDrop(ackCount, rn)) {
            System.out.println("[Receiver] ACK #" + ackCount + " DROPPED (Seq=" + seqNum + ") by ChaosEngine");
            return;
        }

        DSPacket ack = new DSPacket(DSPacket.TYPE_ACK, seqNum, null);
        byte[]         data = ack.toBytes();
        DatagramPacket udp  = new DatagramPacket(data, data.length, senderAddr, senderPort);
        socket.send(udp);
        System.out.println("[Receiver] ACK sent (Seq=" + seqNum + ")");
    }

    public void handshake() throws Exception {
        while (true) {
            DSPacket pkt = receivePacket();

            // See if SOT with Seq=0
            if (pkt.getType() == DSPacket.TYPE_SOT && pkt.getSeqNum() == 0) {
                System.out.println("[Receiver] SOT received (Seq=0)");
                sendACK(0);
                System.out.println("[Receiver] Handshake complete");
                return;
            } else {
                System.out.println("[Receiver] Unexpected packet during handshake — ignoring");
            }
        }
    }

    // Universal Data Transfer 
    public void receiveData() throws Exception {
        int expectedSeq = 1;
        int lastAckSeq = 0;
        DSPacket[] buffer = new DSPacket[128]; // Buffer for GBN out-of-order packets

        System.out.println("[Receiver] Ready for data transfer...");

        while (true) {
            DSPacket pkt = receivePacket();
            int type = pkt.getType();
            int seq = pkt.getSeqNum();

            if (type == DSPacket.TYPE_DATA) {
                System.out.println("[Receiver] Received DATA seq=" + seq + " len=" + pkt.getLength());
                
                // Calculate distance utilizing wrap-around modulo 128 arithmetic
                int dist = (seq - expectedSeq + 128) % 128;
                
                if (dist == 0) {
                    // In-order packet received
                    fos.write(pkt.getPayload());
                    expectedSeq = (expectedSeq + 1) % 128;
                    lastAckSeq = seq;
                    
                    // Deliver any contiguous buffered packets
                    while (buffer[expectedSeq] != null) {
                        DSPacket bufferedPkt = buffer[expectedSeq];
                        fos.write(bufferedPkt.getPayload());
                        System.out.println("[Receiver] Delivered buffered DATA seq=" + expectedSeq);
                        
                        buffer[expectedSeq] = null; // Clear from buffer
                        lastAckSeq = expectedSeq;
                        expectedSeq = (expectedSeq + 1) % 128;
                    }
                    sendACK(lastAckSeq); // Cumulative ACK
                } 
                else if (dist > 0 && dist < 120) {
                    // Out-of-order packet inside future receive window
                    if (buffer[seq] == null) {
                        System.out.println("[Receiver] Buffering out-of-order DATA seq=" + seq);
                        buffer[seq] = pkt;
                    } else {
                        System.out.println("[Receiver] Duplicate out-of-order DATA seq=" + seq + " ignored");
                    }
                    sendACK(lastAckSeq); // Re-send cumulative ACK
                } 
                else {
                    // Old or duplicate packet - out of window
                    System.out.println("[Receiver] Old/Duplicate DATA seq=" + seq + " ignored");
                    sendACK(lastAckSeq);
                }
            } 
            else if (type == DSPacket.TYPE_EOT) {
                System.out.println("[Receiver] Received EOT seq=" + seq);
                sendACK(seq);
                
                fos.close();
                System.out.println("[Receiver] Teardown complete. Exiting.");
                break;
            }
            else if (type == DSPacket.TYPE_SOT) {
                System.out.println("[Receiver] Received duplicate SOT seq=0");
                sendACK(0);
            }
        }
    }

    // entry

    public static void main(String[] args) throws Exception {

       
        if (args.length < 5) {
            System.out.println("Usage: java Receiver <sender_ip> <sender_ack_port> <rcv_data_port> <output_file> <RN>");
            return;
        }

        // Parsing
        String senderIp = args[0]; 
        int senderAckPort = Integer.parseInt(args[1]); 
        int rcvDataPort = Integer.parseInt(args[2]);
        String outputFile = args[3];
        int rn = Integer.parseInt(args[4]);

        Receiver receiver = new Receiver(rcvDataPort, rn, outputFile);

        receiver.handshake();
        receiver.receiveData();
        receiver.socket.close();
    }
}