import java.io.*;
import java.net.*;
import java.util.ArrayList;
import java.util.List;

public class Sender {

    private static final int TIMEOUT_MS = 3000;
    private static final int MAX_TIMEOUTS = 3;

    private DatagramSocket socket;
    private InetAddress receiverAddr;
    private int receiverPort;

    // sets up the socket and connects to the receiver address
    public Sender(String host, int port) throws Exception {
        receiverAddr = InetAddress.getByName(host);
        receiverPort = port;
        socket = new DatagramSocket();
        socket.setSoTimeout(TIMEOUT_MS);
    }

    // wraps a DSPacket into a UDP datagram and sends it
    private void sendPacket(DSPacket pkt) throws Exception {
        byte[] data = pkt.toBytes();
        DatagramPacket udp = new DatagramPacket(data, data.length, receiverAddr, receiverPort);
        socket.send(udp);
    }

    // blocks until a packet arrives and returns it as a DSPacket
    private DSPacket receivePacket() throws Exception {
        byte[] buf = new byte[DSPacket.MAX_PACKET_SIZE];
        DatagramPacket udp = new DatagramPacket(buf, buf.length);
        socket.receive(udp);
        return new DSPacket(udp.getData());
    }

    // sends SOT and waits for ACK(0) to establish connection
    public boolean handshake() throws Exception {
        DSPacket sot = new DSPacket(DSPacket.TYPE_SOT, 0, null);
        int timeouts = 0;

        while (timeouts < MAX_TIMEOUTS) {
            sendPacket(sot);
            System.out.println("[Sender] SOT sent");

            try {
                DSPacket res = receivePacket();
                if (res.getType() == DSPacket.TYPE_ACK && res.getSeqNum() == 0) {
                    System.out.println("[Sender] Handshake complete");
                    return true;
                }
            } catch (SocketTimeoutException e) {
                timeouts++;
                System.out.println("[Sender] Timeout " + timeouts + " waiting for handshake ACK");
            }
        }

        System.out.println("[Sender] Could not connect to receiver");
        return false;
    }

    // stop and wait - sends one packet at a time and waits for its ACK
    public void sendFileStopAndWait(String filename) throws Exception {
        File file = new File(filename);

        if (!file.exists() || file.length() == 0) {
            sendEOT(1);
            return;
        }

        FileInputStream fis = new FileInputStream(file);
        byte[] buf = new byte[DSPacket.MAX_PAYLOAD_SIZE];
        int seq = 1;
        int bytes;

        while ((bytes = fis.read(buf)) != -1) {
            byte[] payload = new byte[bytes];
            System.arraycopy(buf, 0, payload, 0, bytes);

            DSPacket pkt = new DSPacket(DSPacket.TYPE_DATA, seq, payload);
            int timeouts = 0;
            boolean acked = false;

            while (!acked) {
                sendPacket(pkt);
                System.out.println("[Sender] Sent DATA seq=" + seq + " len=" + bytes);

                try {
                    DSPacket res = receivePacket();
                    if (res.getType() == DSPacket.TYPE_ACK && res.getSeqNum() == seq) {
                        System.out.println("[Sender] Got ACK seq=" + seq);
                        acked = true;
                        timeouts = 0;
                        seq = (seq + 1) % 128;
                    } else {
                        System.out.println("[Sender] Wrong ACK, expected " + seq + " got " + res.getSeqNum());
                    }
                } catch (SocketTimeoutException e) {
                    timeouts++;
                    System.out.println("[Sender] Timeout " + timeouts + " on seq=" + seq);
                    if (timeouts >= MAX_TIMEOUTS) {
                        System.out.println("Unable to transfer file.");
                        fis.close();
                        return;
                    }
                }
            }
        }

        fis.close();
        sendEOT(seq);
    }

    // go back n - sends a whole window at a time, retransmits all on timeout
    public void sendFileGBN(String filename, int windowSize) throws Exception {
        File file = new File(filename);

        if (!file.exists() || file.length() == 0) {
            sendEOT(1);
            return;
        }

        // read the whole file into packets first so we can retransmit easily
        List<DSPacket> packets = new ArrayList<>();
        FileInputStream fis = new FileInputStream(file);
        byte[] buf = new byte[DSPacket.MAX_PAYLOAD_SIZE];
        int seq = 1;
        int bytes;

        while ((bytes = fis.read(buf)) != -1) {
            byte[] payload = new byte[bytes];
            System.arraycopy(buf, 0, payload, 0, bytes);
            packets.add(new DSPacket(DSPacket.TYPE_DATA, seq, payload));
            seq = (seq + 1) % 128;
        }
        fis.close();

        int total = packets.size();
        int base = 0;
        int nextToSend = 0;
        int timeouts = 0;

        while (base < total) {
            // send new packets that fit in the window
            List<DSPacket> toSend = new ArrayList<>();
            while (nextToSend < total && nextToSend < base + windowSize) {
                toSend.add(packets.get(nextToSend));
                nextToSend++;
            }

            // apply chaos permutation in groups of 4
            List<DSPacket> permuted = new ArrayList<>();
            for (int i = 0; i < toSend.size(); i += 4) {
                List<DSPacket> group = new ArrayList<>(toSend.subList(i, Math.min(i + 4, toSend.size())));
                permuted.addAll(ChaosEngine.permutePackets(group));
            }

            for (DSPacket pkt : permuted) {
                sendPacket(pkt);
                System.out.println("[Sender] GBN sent seq=" + pkt.getSeqNum());
            }

            // wait for an ACK
            try {
                DSPacket ack = receivePacket();

                if (ack.getType() == DSPacket.TYPE_ACK) {
                    int ackedSeq = ack.getSeqNum();
                    int newBase = base;

                    // figure out how far forward this ACK moves our window
                    for (int i = base; i < nextToSend; i++) {
                        if (packets.get(i).getSeqNum() == ackedSeq) {
                            newBase = i + 1;
                            break;
                        }
                    }

                    if (newBase > base) {
                        System.out.println("[Sender] GBN ACK seq=" + ackedSeq + " window now at " + newBase);
                        base = newBase;
                        timeouts = 0;
                    } else {
                        System.out.println("[Sender] GBN stale ACK seq=" + ackedSeq + " ignoring");
                    }
                }

            } catch (SocketTimeoutException e) {
                timeouts++;
                System.out.println("[Sender] GBN timeout " + timeouts + " retransmitting from " + base);

                if (timeouts >= MAX_TIMEOUTS) {
                    System.out.println("Unable to transfer file.");
                    return;
                }

                nextToSend = base;
            }
        }

        int eotSeq = (packets.get(total - 1).getSeqNum() + 1) % 128;
        sendEOT(eotSeq);
    }

    // sends EOT and waits for ACK
    private void sendEOT(int seq) throws Exception {
        DSPacket eot = new DSPacket(DSPacket.TYPE_EOT, seq, null);
        int timeouts = 0;

        while (timeouts < MAX_TIMEOUTS) {
            sendPacket(eot);
            System.out.println("[Sender] EOT sent seq=" + seq);

            try {
                DSPacket res = receivePacket();
                if (res.getType() == DSPacket.TYPE_ACK && res.getSeqNum() == seq) {
                    System.out.println("[Sender] Transfer complete");
                    return;
                }
            } catch (SocketTimeoutException e) {
                timeouts++;
                System.out.println("[Sender] Timeout " + timeouts + " waiting for EOT ACK");
            }
        }

        System.out.println("[Sender] No EOT ACK received, giving up");
    }

    // entry point, parses args and kicks off the handshake and transfer
    public static void main(String[] args) throws Exception {
        if (args.length < 4) {
            System.out.println("Usage: java Sender <host> <port> <file> <saw|gbn> [window_size]");
            return;
        }

        String host = args[0];
        int port = Integer.parseInt(args[1]);
        String filename = args[2];
        String mode = args[3].toLowerCase();

        Sender sender = new Sender(host, port);

        if (!sender.handshake()) {
            sender.socket.close();
            return;
        }

        if (mode.equals("saw")) {
            sender.sendFileStopAndWait(filename);
        } else if (mode.equals("gbn")) {
            if (args.length < 5) {
                System.out.println("[Sender] need window size for gbn");
                sender.socket.close();
                return;
            }
            int windowSize = Integer.parseInt(args[4]);
            sender.sendFileGBN(filename, windowSize);
        } else {
            System.out.println("[Sender] unknown mode: " + mode);
        }

        sender.socket.close();
    }
}