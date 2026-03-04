import java.net.*;

public class Receiver {

    // ── tunables ──────────────────────────────────────────────────────────────
    private static final int TIMEOUT_MS   = 3000;  // socket timeout
    private static final int MAX_TIMEOUTS = 3;      // consecutive timeout limit

    // ── state ─────────────────────────────────────────────────────────────────
    private DatagramSocket socket;
    private InetAddress    senderAddr;
    private int            senderPort;
    private int            ackCount = 0;   // 1-indexed ACK counter for ChaosEngine
    private int            rn;             // reliability number

    // ── constructor ───────────────────────────────────────────────────────────
    public Receiver(int port, int rn) throws Exception {
        this.socket = new DatagramSocket(port);  // bind to known port
        this.socket.setSoTimeout(TIMEOUT_MS);
        this.rn = rn;
        System.out.println("[Receiver] Listening on port " + port);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /** Block until a 128-byte datagram arrives; records sender address for replies. */
    private DSPacket receivePacket() throws Exception {
        byte[]         buf = new byte[DSPacket.MAX_PACKET_SIZE];
        DatagramPacket udp = new DatagramPacket(buf, buf.length);
        socket.receive(udp);

        // Remember who sent this so we can reply
        this.senderAddr = udp.getAddress();
        this.senderPort = udp.getPort();

        return new DSPacket(udp.getData());
    }

    /** Send any DSPacket back to the sender, respecting ChaosEngine ACK drops. */
    private void sendACK(DSPacket pkt) throws Exception {
        ackCount++;  // 1-indexed

        if (ChaosEngine.shouldDrop(ackCount, rn)) {
            System.out.println("[Receiver] ACK #" + ackCount + " DROPPED (Seq=" + pkt.getSeqNum() + ") by ChaosEngine");
            return;
        }

        byte[]         data = pkt.toBytes();
        DatagramPacket udp  = new DatagramPacket(data, data.length, senderAddr, senderPort);
        socket.send(udp);
        System.out.println("[Receiver] ACK sent (Seq=" + pkt.getSeqNum() + ")");
    }

    // ── Phase 1: Handshake ────────────────────────────────────────────────────

    /**
     * Waits for SOT (Type=0, Seq=0) from sender, replies with ACK(0).
     * Retries up to MAX_TIMEOUTS times if nothing arrives.
     *
     * @return true if handshake succeeded, false if max timeouts exceeded
     */
    public boolean handshake() throws Exception {
        int timeoutCount = 0;

        while (timeoutCount < MAX_TIMEOUTS) {
            try {
                DSPacket pkt = receivePacket();

                // Validate: must be SOT with Seq=0
                if (pkt.getType() == DSPacket.TYPE_SOT && pkt.getSeqNum() == 0) {
                    System.out.println("[Receiver] SOT received (Seq=0)");

                    // Reply with ACK(0)
                    DSPacket ack = new DSPacket(DSPacket.TYPE_ACK, 0, null);
                    sendACK(ack);

                    System.out.println("[Receiver] Handshake complete");
                    return true;
                } else {
                    System.out.println("[Receiver] Unexpected packet during handshake — ignoring");
                }

            } catch (SocketTimeoutException e) {
                timeoutCount++;
                System.out.println("[Receiver] Timeout #" + timeoutCount + " waiting for SOT");
            }
        }

        System.out.println("[Receiver] ERROR: No SOT received after " + MAX_TIMEOUTS + " timeouts");
        return false;
    }

    // ── Entry point ───────────────────────────────────────────────────────────

    /**
     * Usage: java Receiver <rcv_data_port> <output_file> <mode> [rn]
     *   rcv_data_port : port to listen on
     *   output_file   : file to write received data to
     *   mode          : saw (Stop-and-Wait) | gbn (Go-Back-N)
     *   rn            : reliability number (optional, default 0 = no loss)
     */
    public static void main(String[] args) throws Exception {

        if (args.length < 3) {
            System.out.println("Usage: java Receiver <port> <output_file> <saw|gbn> [rn]");
            return;
        }

        int    port       = Integer.parseInt(args[0]);
        String outputFile = args[1];
        String mode       = args[2].toLowerCase();
        int    rn         = args.length >= 4 ? Integer.parseInt(args[3]) : 0;

        Receiver receiver = new Receiver(port, rn);

        // ── Phase 1: Handshake ────────────────────────────────────────────────
        if (!receiver.handshake()) {
            receiver.socket.close();
            return;
        }

        // ── Phase 2: Data Transfer (to be implemented) ────────────────────────
        if (mode.equals("saw")) {
            System.out.println("[Receiver] Mode: Stop-and-Wait (to be implemented)");
            // TODO: receiver.receiveFileStopAndWait(outputFile);
        } else if (mode.equals("gbn")) {
            System.out.println("[Receiver] Mode: Go-Back-N (to be implemented)");
            // TODO: receiver.receiveFileGBN(outputFile);
        } else {
            System.out.println("[Receiver] ERROR: unknown mode '" + mode + "'");
        }

        receiver.socket.close();
    }
}
