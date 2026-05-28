package audiometry.gui;

import audiometry.AudiometryResult;
import audiometry.AudiometryResult.Maybe;
import audiometry.HughsonWestlake;
import audiometry.HughsonWestlake.AudioTestState;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.SourceDataLine;
import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.io.File;
import java.io.IOException;

public class VirtualAudiometerApp extends JFrame {

    private final int[] FREQUENCIES = {250, 500, 1000, 2000, 4000, 8000};
    private int currentFreqIndex = 0;
    
    private AudioTestState currentState = null;
    private Timer timeoutTimer;
    private final int TIMEOUT_MS = 3000;
    
    // Kullanıcı Arayüzü (UI) Bileşenleri
    private JComboBox<String> portSelector;
    private JButton connectBtn;
    private JRadioButton rightEarBtn;
    private JRadioButton leftEarBtn;

    private JButton startBtn;
    private JButton responseBtn;
    private JButton saveBtn;

    private SerialPortManager serialManager = new SerialPortManager();
    
    private JLabel statusLabel;
    private JLabel currentFreqLabel;
    private JLabel currentDbLabel;
    
    private AudiogramPanel audiogramPanel;

    public VirtualAudiometerApp() {
        setTitle("Virtual Audiometer (YMH 334 / COM 2044)"); // Başlık
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE); // Kapatma butonu
        setSize(800, 600); // pencere boyutu
        setLayout(new BorderLayout()); // duzen tipini ayarla

        initUI(); 
        initTimer(); // zamanalayıcıyı kur
        
        setLocationRelativeTo(null); // pencereyi ortala
    }

    private void initUI() {
        // Kurulum Paneli (COM & Ear Selection)
        JPanel setupPanel = new JPanel(new FlowLayout());
        portSelector = new JComboBox<>(SerialPortManager.getAvailablePorts());
        connectBtn = new JButton("Connect");
        
        rightEarBtn = new JRadioButton("Right Ear (Red O)", true);
        leftEarBtn = new JRadioButton("Left Ear (Blue X)", false);
        ButtonGroup earGroup = new ButtonGroup();
        earGroup.add(rightEarBtn);
        earGroup.add(leftEarBtn);
        
        setupPanel.add(new JLabel("COM Port:"));
        setupPanel.add(portSelector);
        setupPanel.add(connectBtn);
        setupPanel.add(rightEarBtn);
        setupPanel.add(leftEarBtn);

        // Üst Kontrol Paneli
        JPanel controlPanel = new JPanel(new FlowLayout());
        startBtn = new JButton("Start Test");
        responseBtn = new JButton("Patient Response (Virtual Button)");
        saveBtn = new JButton("Save Audiogram");
        
        responseBtn.setEnabled(false);
        saveBtn.setEnabled(false);
        
        controlPanel.add(startBtn);
        controlPanel.add(responseBtn);
        controlPanel.add(saveBtn);

        // Durum Paneli
        JPanel statusPanel = new JPanel(new GridLayout(1, 3));
        statusPanel.setBorder(BorderFactory.createTitledBorder("Test Status"));
        
        statusLabel = new JLabel("Status: Waiting to start...", SwingConstants.CENTER);
        currentFreqLabel = new JLabel("Freq: -- Hz", SwingConstants.CENTER);
        currentDbLabel = new JLabel("Intensity: -- dB", SwingConstants.CENTER);
        
        statusPanel.add(statusLabel);
        statusPanel.add(currentFreqLabel);
        statusPanel.add(currentDbLabel);

        // Orta Panel (Odyogram)
        audiogramPanel = new AudiogramPanel();
        
        // Pencereye (Frame) Ekle
        JPanel topPanel = new JPanel(new BorderLayout());
        topPanel.add(setupPanel, BorderLayout.NORTH);
        topPanel.add(controlPanel, BorderLayout.SOUTH);

        add(topPanel, BorderLayout.NORTH);
        add(audiogramPanel, BorderLayout.CENTER);
        add(statusPanel, BorderLayout.SOUTH);

        // Buton Dinleyicileri (Action Listeners)
        connectBtn.addActionListener(e -> {
            String port = (String) portSelector.getSelectedItem();
            if (port != null && serialManager.connect(port)) {
                JOptionPane.showMessageDialog(this, "Connected to " + port);
                connectBtn.setEnabled(false);
                portSelector.setEnabled(false);
            } else {
                JOptionPane.showMessageDialog(this, "Failed to connect!");
            }
        });
        
        serialManager.setOnMessageReceived(msg -> {
            if ("RESPONSE".equals(msg.trim()) && currentState != null) {
                SwingUtilities.invokeLater(() -> {
                    if (responseBtn.isEnabled()) {
                        handlePatientResponse();
                    }
                });
            }
        });

        startBtn.addActionListener(e -> startTestSequence());
        responseBtn.addActionListener(e -> handlePatientResponse());
        saveBtn.addActionListener(e -> saveAudiogram());
    }

    private void initTimer() {
        timeoutTimer = new Timer(TIMEOUT_MS, (ActionEvent e) -> handleNoResponse());
        timeoutTimer.setRepeats(false);
    }

    private void startTestSequence() {
        currentFreqIndex = 0; // ilk frekanstan başla
        audiogramPanel.clear(); // grafiği temizle
        startBtn.setEnabled(false); // başla butonunu kapat
        saveBtn.setEnabled(false); // kaydet butonunu kapat
        startNextFrequency(); // ilk sesi başlat
    }

    private void startNextFrequency() {
        if (currentFreqIndex >= FREQUENCIES.length) {
            endTestSequence();
            return;
        }

        int freq = FREQUENCIES[currentFreqIndex];
        int startDb = 30; // Standartlara göre başlangıç şiddeti (30 dB)
        
        Maybe<AudioTestState> initResult = AudiometryResult.initializeTest(freq, startDb);
        
        if (initResult.isSuccess()) {
            currentState = initResult.getValue();
            updateUIForState();
            playCurrentSound();
        } else {
            JOptionPane.showMessageDialog(this, "Error initializing test: " + initResult.getError());
            endTestSequence();
        }
    }

    private void playCurrentSound() {
        if (currentState == null) return;
        
        statusLabel.setText("Status: Playing Sound...");
        responseBtn.setEnabled(true);
        
        // Hasta yanıtı için 3 saniyelik zamanlayıcıyı başlat
        timeoutTimer.restart();
        
        // Serial porta komut gönder
        serialManager.sendCommand(String.format("PLAY,%d,%d", currentState.frequencyHz, currentState.intensityDb));
        try {
    String udpCmd = String.format("PLAY,%d,%d", currentState.frequencyHz, currentState.intensityDb);
    java.net.DatagramSocket udpSocket = new java.net.DatagramSocket();
    java.net.DatagramPacket udpPacket = new java.net.DatagramPacket(
        udpCmd.getBytes(), 
        udpCmd.getBytes().length, 
        java.net.InetAddress.getByName("127.0.0.1"), 
        25000
    );
    udpSocket.send(udpPacket);
    udpSocket.close();
    System.out.println("--> Simulink'e UDP Basariyla Gonderildi: " + udpCmd);
} catch (Exception e) {
    System.out.println("UDP Hatasi: " + e.getMessage());
}
        // Gerçek sesi asenkron (arka planda) olarak 1000ms boyunca çal
        playSoundAsync(currentState.frequencyHz, currentState.intensityDb, 1000);
    }

    private void playSoundAsync(int hz, int db, int msecs) {
        new Thread(() -> {
            try {
                float sampleRate = 44100;
                AudioFormat af = new AudioFormat(sampleRate, 16, 1, true, true);
                SourceDataLine sdl = AudioSystem.getSourceDataLine(af);
                sdl.open(af);
                sdl.start();
                
                // dB değerini (30 - 120) genliğe (0.0 - 1.0) dönüştür
                // 120 dB = 1.0 genlik. Her 20 dB genliği 10 kat değiştirir.
                double maxDb = 120.0;
                double amplitude = Math.pow(10.0, (db - maxDb) / 20.0);
                if (amplitude > 1.0) amplitude = 1.0;
                
                byte[] buffer = new byte[(int)(sampleRate * msecs / 1000) * 2];
                for (int i = 0; i < buffer.length / 2; i++) {
                    double angle = i / (sampleRate / hz) * 2.0 * Math.PI;
                    short val = (short)(Math.sin(angle) * 32767 * amplitude);
                    buffer[2*i] = (byte)(val >> 8);
                    buffer[2*i+1] = (byte)val;
                }
                sdl.write(buffer, 0, buffer.length);
                sdl.drain();
                sdl.stop();
                sdl.close();
            } catch (Exception e) {
                System.err.println("Error playing sound: " + e.getMessage());
            }
        }).start();
    }

    private void handlePatientResponse() {
        timeoutTimer.stop(); // süreyi durdur
        responseBtn.setEnabled(false); // butonu geçici olarak kapat
        
        Maybe<AudioTestState> processResult = AudiometryResult.processSerialInput("RESPONSE", currentState); // yanıtı işle

        
        if (processResult.isSuccess()) {
            currentState = processResult.getValue();
            checkTestCompletion();
        } else {
            System.err.println("Error processing response: " + processResult.getError());
        }
    }

    private void handleNoResponse() {
        responseBtn.setEnabled(false); // butonu kapat
        currentState = HughsonWestlake.onNoResponse(currentState); // yanit yok durumunu işle
        checkTestCompletion(); // bitip bitmediğini kontrol et
    }

    private void checkTestCompletion() {
        updateUIForState();
        
        if (HughsonWestlake.isTestComplete(currentState)) {
            Maybe<Integer> thresholdResult = AudiometryResult.safeGetThreshold(currentState);
            
            if (thresholdResult.isSuccess()) {
                int threshold = thresholdResult.getValue();
                boolean isRightEar = rightEarBtn.isSelected();
                audiogramPanel.addThreshold(currentState.frequencyHz, threshold, isRightEar);
                statusLabel.setText("Threshold found: " + threshold + " dB @ " + currentState.frequencyHz + " Hz");
            } else {
                statusLabel.setText("No Response at max dB for " + currentState.frequencyHz + " Hz");
                // Standarda göre 120 dB'de (maksimum) işaretle veya boş bırak. Biz boş bırakıyoruz.
            }
            
            // Kısa bir gecikmeden sonra bir sonraki frekansa geç
            Timer delay = new Timer(1500, e -> {
                currentFreqIndex++;
                startNextFrequency();
            });
            delay.setRepeats(false);
            delay.start();
            
        } else {
            // Test henüz tamamlanmadıysa, bir sonraki sesi çalmak için programla
            statusLabel.setText("Status: Waiting to play next sound...");
            Timer delay = new Timer(1000, e -> playCurrentSound());
            delay.setRepeats(false);
            delay.start();
        }
    }

    private void updateUIForState() {
        if (currentState != null) {
            currentFreqLabel.setText(String.format("Freq: %d Hz", currentState.frequencyHz));
            currentDbLabel.setText(String.format("Intensity: %d dB", currentState.intensityDb));
        }
    }

    private void endTestSequence() {
        statusLabel.setText("Status: Test Complete!"); // test bitti yazısı
        currentFreqLabel.setText("Freq: -- Hz"); // frekansı sıfırla
        currentDbLabel.setText("Intensity: -- dB"); // siddeti sıfırla
        
        startBtn.setEnabled(true); 
        responseBtn.setEnabled(false);
        saveBtn.setEnabled(true);
        currentState = null; // durumu sıfırla
    }
    
    private void saveAudiogram() {
        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setDialogTitle("Save Audiogram");
        int userSelection = fileChooser.showSaveDialog(this);
        
        if (userSelection == JFileChooser.APPROVE_OPTION) {
            File fileToSave = fileChooser.getSelectedFile();
            if (!fileToSave.getName().toLowerCase().endsWith(".png")) {
                fileToSave = new File(fileToSave.getParentFile(), fileToSave.getName() + ".png");
            }
            
            try {
                audiogramPanel.saveAsPng(fileToSave);
                JOptionPane.showMessageDialog(this, "Audiogram saved successfully!");
            } catch (IOException ex) {
                JOptionPane.showMessageDialog(this, "Error saving image: " + ex.getMessage());
            }
        }
    }

    public static void main(String[] args) {
        // İşletim sisteminin varsayılan arayüz temasını kullan
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {}
        
        SwingUtilities.invokeLater(() -> {
            new VirtualAudiometerApp().setVisible(true);
        });
    }
}
