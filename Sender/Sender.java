import java.net.*;

public class Sender {

    private static final int TIMEOUT_MS      = 3000;   // 3-second socket timeout
    private static final int MAX_TIMEOUTS    = 3;       // consecutive timeout limit

    private DatagramSocket socket;
    private InetAddress    receiverAddr;
    private int            receiverPort;

    public Sender(String host, int port) throws Exception {
        this.receiverAddr = InetAddress.getByName(host);
        this.receiverPort = port;
        this.socket       = new DatagramSocket();          // OS picks a free local port
        this.socket.setSoTimeout(TIMEOUT_MS);
    }

    /** Send any DSPacket to the receiver. */
    private void sendPacket(DSPacket pkt) throws Exception {
        byte[]         data = pkt.toBytes();
        DatagramPacket udp  = new DatagramPacket(data, data.length, receiverAddr, receiverPort);
        socket.send(udp);
    }

    /** Block until a 128-byte datagram arrives; return it as a DSPacket. */
    private DSPacket receivePacket() throws Exception {
        byte[]         buf = new byte[DSPacket.MAX_PACKET_SIZE];
        DatagramPacket udp = new DatagramPacket(buf, buf.length);
        socket.receive(udp);
        return new DSPacket(udp.getData());
    }

    // ── Phase 1: Handshake ────────────────────────────────────────────────────

    /**
     * Sends SOT (Type=0, Seq=0) and waits for ACK (Type=2, Seq=0).
     * Retries up to MAX_TIMEOUTS times on timeout.
     *
     * @return true if handshake succeeded, false if max retries exceeded
     */
    public boolean handshake() throws Exception {
        DSPacket sot          = new DSPacket(DSPacket.TYPE_SOT, 0, null);
        int      timeoutCount = 0;

        while (timeoutCount < MAX_TIMEOUTS) {
            // Send SOT
            sendPacket(sot);
            System.out.println("[Sender] SOT sent (Seq=0)");

            try {
                DSPacket response = receivePacket();

                // Validate: must be ACK with Seq=0
                if (response.getType() == DSPacket.TYPE_ACK && response.getSeqNum() == 0) {
                    System.out.println("[Sender] Handshake complete — ACK(0) received");
                    return true;
                } else {
                    System.out.println("[Sender] Unexpected packet during handshake — ignoring");
                }

            } catch (SocketTimeoutException e) {
                timeoutCount++;
                System.out.println("[Sender] Timeout #" + timeoutCount + " waiting for SOT ACK");
            }
        }

        // 3 consecutive timeouts — give up
        System.out.println("[Sender] ERROR: Connection failed after " + MAX_TIMEOUTS + " timeouts");
        return false;
    }

    // ── Entry point ───────────────────────────────────────────────────────────

    /**
     * Usage: java Sender <receiver_host> <receiver_port> <filename> <mode> [window_size] [rn]
     *   mode        : saw  (Stop-and-Wait)  |  gbn  (Go-Back-N)
     *   window_size : required for GBN
     *   rn          : reliability number (optional, default 0 = no loss)
     */
    public static void main(String[] args) throws Exception {

        if (args.length < 4) {
            System.out.println("Usage: java Sender <host> <port> <file> <saw|gbn> [window_size] [rn]");
            return;
        }

        String host     = args[0];
        int    port     = Integer.parseInt(args[1]);
        String filename = args[2];
        String mode     = args[3].toLowerCase();

        Sender sender = new Sender(host, port);

        // ── Phase 1: Handshake ────────────────────────────────────────────────
        if (!sender.handshake()) {
            sender.socket.close();
            return;
        }

        // ── Phase 2: Data Transfer (to be implemented) ────────────────────────
        if (mode.equals("saw")) {
            System.out.println("[Sender] Mode: Stop-and-Wait (to be implemented)");
            // TODO: sender.sendFileStopAndWait(filename);
        } else if (mode.equals("gbn")) {
            if (args.length < 5) {
                System.out.println("[Sender] ERROR: window_size required for GBN mode");
                sender.socket.close();
                return;
            }
            int windowSize = Integer.parseInt(args[4]);
            System.out.println("[Sender] Mode: Go-Back-N  window=" + windowSize + " (to be implemented)");
            // TODO: sender.sendFileGBN(filename, windowSize);
        } else {
            System.out.println("[Sender] ERROR: unknown mode '" + mode + "'");
        }

        sender.socket.close();
    }
}