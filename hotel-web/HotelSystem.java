/*
 * Hotel Reservation System (Java 17+, no external libraries)
 * ----------------------------------------------------------
 * OOP design + File I/O persistence (CSV files in ./hotel_data).
 *
 * Run (Java 17+):   javac HotelSystem.java && java HotelSystem
 *            or:    java HotelSystem.java
 *
 * Classes
 *   RoomCategory      enum: STANDARD / DELUXE / SUITE (rate + capacity)
 *   Room, Guest       value objects (records)
 *   Reservation       a booking (mutable status/refund)
 *   Payment           payment / refund transaction record
 *   PaymentGateway    simulated card processing (Luhn + decline rule)
 *   FileStore         File I/O layer: loads/saves CSV files atomically
 *   HotelService      business logic: search, book, cancel, view
 *   Cli               menu-driven interface
 */
import java.io.IOException;
import java.nio.file.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

public class HotelSystem {
    public static void main(String[] args) throws IOException {
        Path dir = Paths.get(args.length > 0 ? args[0] : "hotel_data");
        new Cli(new HotelService(new FileStore(dir))).run();
    }
}

// ---------------------------------------------------------------------------
// Domain model
// ---------------------------------------------------------------------------
enum RoomCategory {
    STANDARD(80.0, 2), DELUXE(140.0, 3), SUITE(260.0, 5);

    final double rate;      // per night
    final int capacity;     // max guests

    RoomCategory(double rate, int capacity) { this.rate = rate; this.capacity = capacity; }

    String title() { return name().charAt(0) + name().substring(1).toLowerCase(); }
}

record Room(String roomNo, RoomCategory category) {
    double rate() { return category.rate; }
}

record Guest(String name, String email, String phone) { }

record Payment(String payId, String resId, double amount, String method,
               String status, String createdAt) { }

enum ReservationStatus { CONFIRMED, CANCELLED }

class Reservation {
    final String resId;
    final Guest guest;
    final Room room;
    final LocalDate checkIn, checkOut;
    final int guestCount;
    final double total;
    ReservationStatus status = ReservationStatus.CONFIRMED;
    double refund = 0.0;

    Reservation(String resId, Guest guest, Room room, LocalDate checkIn,
                LocalDate checkOut, int guestCount, double total) {
        this.resId = resId; this.guest = guest; this.room = room;
        this.checkIn = checkIn; this.checkOut = checkOut;
        this.guestCount = guestCount; this.total = total;
    }

    long nights() { return ChronoUnit.DAYS.between(checkIn, checkOut); }
}

class BookingException extends RuntimeException {
    BookingException(String msg) { super(msg); }
}

class PaymentException extends Exception {
    PaymentException(String msg) { super(msg); }
}

// ---------------------------------------------------------------------------
// Payment simulation
// ---------------------------------------------------------------------------
/** Fake gateway. Card must be 16 digits + pass Luhn; cards ending 0000 are declined.
 *  Working test card: 4242 4242 4242 4242 */
class PaymentGateway {
    private static boolean luhn(String number) {
        int sum = 0; boolean alt = false;
        for (int i = number.length() - 1; i >= 0; i--) {
            int d = number.charAt(i) - '0';
            if (alt) { d *= 2; if (d > 9) d -= 9; }
            sum += d; alt = !alt;
        }
        return sum % 10 == 0;
    }

    String charge(String card, double amount) throws PaymentException {
        String c = card.replace(" ", "");
        if (!c.matches("\\d{16}") || !luhn(c)) throw new PaymentException("Invalid card number.");
        if (c.endsWith("0000")) throw new PaymentException("Card declined by issuer.");
        return "TXN-" + UUID.randomUUID().toString().substring(0, 10).toUpperCase();
    }

    String refund(double amount) {
        return "RFD-" + UUID.randomUUID().toString().substring(0, 10).toUpperCase();
    }
}

// ---------------------------------------------------------------------------
// File I/O layer (CSV). Data is loaded into memory and re-written on change.
// Writes go to a temp file first, then an atomic move, so a crash can't
// leave a half-written file.
// ---------------------------------------------------------------------------
class FileStore {
    final List<Room> rooms = new ArrayList<>();
    final List<Reservation> reservations = new ArrayList<>();
    final List<Payment> payments = new ArrayList<>();

    private final Path roomsFile, resFile, payFile;

    FileStore(Path dir) throws IOException {
        Files.createDirectories(dir);
        roomsFile = dir.resolve("rooms.csv");
        resFile = dir.resolve("reservations.csv");
        payFile = dir.resolve("payments.csv");

        if (!Files.exists(roomsFile)) { seedRooms(); saveRooms(); }
        loadRooms();
        loadReservations();
        loadPayments();
    }

    private void seedRooms() {
        for (String n : new String[]{"101", "102", "103", "104", "105"})
            rooms.add(new Room(n, RoomCategory.STANDARD));
        for (String n : new String[]{"201", "202", "203", "204"})
            rooms.add(new Room(n, RoomCategory.DELUXE));
        for (String n : new String[]{"301", "302"})
            rooms.add(new Room(n, RoomCategory.SUITE));
    }

    // ---- helpers ----
    private static String clean(String s) { return s.replace(",", " ").replace("\n", " ").trim(); }

    private static List<String[]> readRows(Path p) throws IOException {
        if (!Files.exists(p)) return List.of();
        return Files.readAllLines(p).stream()
                .filter(l -> !l.isBlank())
                .map(l -> l.split(",", -1))
                .collect(Collectors.toList());
    }

    private static void writeAtomic(Path p, List<String> lines) throws IOException {
        Path tmp = p.resolveSibling(p.getFileName() + ".tmp");
        Files.write(tmp, lines);
        Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    // ---- load ----
    private void loadRooms() throws IOException {
        rooms.clear();
        for (String[] f : readRows(roomsFile))
            rooms.add(new Room(f[0], RoomCategory.valueOf(f[1])));
    }

    private void loadReservations() throws IOException {
        reservations.clear();
        for (String[] f : readRows(resFile)) {
            Room room = rooms.stream().filter(r -> r.roomNo().equals(f[4])).findFirst().orElseThrow();
            Reservation r = new Reservation(f[0], new Guest(f[1], f[2], f[3]), room,
                    LocalDate.parse(f[5]), LocalDate.parse(f[6]),
                    Integer.parseInt(f[7]), Double.parseDouble(f[8]));
            r.status = ReservationStatus.valueOf(f[9]);
            r.refund = Double.parseDouble(f[10]);
            reservations.add(r);
        }
    }

    private void loadPayments() throws IOException {
        payments.clear();
        for (String[] f : readRows(payFile))
            payments.add(new Payment(f[0], f[1], Double.parseDouble(f[2]), f[3], f[4], f[5]));
    }

    // ---- save ----
    void saveRooms() throws IOException {
        writeAtomic(roomsFile, rooms.stream()
                .map(r -> r.roomNo() + "," + r.category().name()).toList());
    }

    void saveReservations() throws IOException {
        writeAtomic(resFile, reservations.stream().map(r -> String.join(",",
                r.resId, clean(r.guest.name()), clean(r.guest.email()), clean(r.guest.phone()),
                r.room.roomNo(), r.checkIn.toString(), r.checkOut.toString(),
                String.valueOf(r.guestCount), String.valueOf(r.total),
                r.status.name(), String.valueOf(r.refund))).toList());
    }

    void savePayments() throws IOException {
        writeAtomic(payFile, payments.stream().map(p -> String.join(",",
                p.payId(), p.resId(), String.valueOf(p.amount()),
                clean(p.method()), p.status(), p.createdAt())).toList());
    }
}

// ---------------------------------------------------------------------------
// Business logic
// ---------------------------------------------------------------------------
class HotelService {
    private final FileStore store;
    private final PaymentGateway gateway = new PaymentGateway();

    HotelService(FileStore store) { this.store = store; }

    private static void validateDates(LocalDate in, LocalDate out) {
        if (in.isBefore(LocalDate.now())) throw new BookingException("Check-in cannot be in the past.");
        if (!out.isAfter(in)) throw new BookingException("Check-out must be after check-in.");
    }

    /** A room is free if no CONFIRMED reservation overlaps [in, out). */
    synchronized List<Room> availableRooms(LocalDate in, LocalDate out, RoomCategory cat) {
        return store.rooms.stream()
                .filter(r -> cat == null || r.category() == cat)
                .filter(r -> store.reservations.stream().noneMatch(res ->
                        res.status == ReservationStatus.CONFIRMED
                                && res.room.roomNo().equals(r.roomNo())
                                && res.checkIn.isBefore(out) && res.checkOut.isAfter(in)))
                .sorted(Comparator.comparing(Room::roomNo))
                .toList();
    }

    synchronized List<Room> search(LocalDate in, LocalDate out, int guests, RoomCategory cat) {
        validateDates(in, out);
        return availableRooms(in, out, cat).stream()
                .filter(r -> r.category().capacity >= guests).toList();
    }

    synchronized Reservation book(Guest guest, String roomNo, LocalDate in, LocalDate out,
                                  int guestCount, String card) {
        validateDates(in, out);
        if (guest.name().isBlank() || !guest.email().contains("@"))
            throw new BookingException("A guest name and valid email are required.");

        Room room = store.rooms.stream().filter(r -> r.roomNo().equals(roomNo)).findFirst()
                .orElseThrow(() -> new BookingException("Room " + roomNo + " does not exist."));
        if (guestCount < 1 || guestCount > room.category().capacity)
            throw new BookingException(room.category().title() + " sleeps 1-" + room.category().capacity + ".");
        if (availableRooms(in, out, null).stream().noneMatch(r -> r.roomNo().equals(roomNo)))
            throw new BookingException("Room " + roomNo + " is not available for those dates.");

        long nights = ChronoUnit.DAYS.between(in, out);
        double total = Math.round(nights * room.rate() * 100.0) / 100.0;

        String txn;
        try {
            txn = gateway.charge(card, total);
        } catch (PaymentException e) {
            throw new BookingException("Payment failed: " + e.getMessage());
        }

        Reservation res = new Reservation("RES-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(),
                guest, room, in, out, guestCount, total);
        Payment pay = new Payment(txn, res.resId, total, "CARD ****" + card.substring(card.length() - 4),
                "SUCCESS", now());

        store.reservations.add(res);
        store.payments.add(pay);
        try {
            store.saveReservations();
            store.savePayments();
        } catch (IOException e) {            // roll back the in-memory state
            store.reservations.remove(res);
            store.payments.remove(pay);
            throw new BookingException("Could not save booking: " + e.getMessage());
        }
        return res;
    }

    /** Policy: >=2 days before check-in 100%, 1 day 50%, otherwise 0%. */
    static double refundAmount(Reservation r, LocalDate today) {
        long daysLeft = ChronoUnit.DAYS.between(today, r.checkIn);
        if (daysLeft >= 2) return r.total;
        if (daysLeft == 1) return Math.round(r.total * 50.0) / 100.0;
        return 0.0;
    }

    synchronized Reservation cancel(String resId) {
        Reservation res = find(resId);
        if (res.status == ReservationStatus.CANCELLED)
            throw new BookingException("Reservation is already cancelled.");
        if (!res.checkOut.isAfter(LocalDate.now()))
            throw new BookingException("Cannot cancel a past stay.");

        double refund = refundAmount(res, LocalDate.now());
        res.status = ReservationStatus.CANCELLED;
        res.refund = refund;
        Payment refundPay = null;
        if (refund > 0) {
            refundPay = new Payment(gateway.refund(refund), resId, -refund, "REFUND", "REFUNDED", now());
            store.payments.add(refundPay);
        }
        try {
            store.saveReservations();
            store.savePayments();
        } catch (IOException e) {            // roll back
            res.status = ReservationStatus.CONFIRMED;
            res.refund = 0;
            if (refundPay != null) store.payments.remove(refundPay);
            throw new BookingException("Could not save cancellation: " + e.getMessage());
        }
        return res;
    }

    synchronized Reservation find(String resId) {
        return store.reservations.stream().filter(r -> r.resId.equalsIgnoreCase(resId)).findFirst()
                .orElseThrow(() -> new BookingException("Reservation not found."));
    }

    synchronized List<Payment> paymentsFor(String resId) {
        return store.payments.stream().filter(p -> p.resId().equalsIgnoreCase(resId)).toList();
    }

    synchronized List<Reservation> myBookings(String email) {
        return store.reservations.stream()
                .filter(r -> r.guest.email().equalsIgnoreCase(email))
                .sorted(Comparator.comparing(r -> r.checkIn)).toList();
    }

    private static String now() { return LocalDateTime.now().withNano(0).toString(); }
}

// ---------------------------------------------------------------------------
// Command-line interface
// ---------------------------------------------------------------------------
class Cli {
    private final HotelService svc;
    private final Scanner in = new Scanner(System.in);

    Cli(HotelService svc) { this.svc = svc; }

    void run() {
        while (true) {
            System.out.print("""

                --- Hotel Reservation System ---
                1) Search available rooms
                2) Make a reservation
                3) Cancel a reservation
                4) View booking details
                5) My bookings (by email)
                0) Exit
                >\s""");
            if (!in.hasNextLine()) return;
            String choice = in.nextLine().trim();
            try {
                switch (choice) {
                    case "1" -> search();
                    case "2" -> book();
                    case "3" -> {
                        Reservation r = svc.cancel(ask("Reservation ID: ").toUpperCase());
                        System.out.printf("Cancelled %s. Refund: $%.2f%n", r.resId, r.refund);
                    }
                    case "4" -> details(ask("Reservation ID: ").toUpperCase());
                    case "5" -> myBookings();
                    case "0" -> { return; }
                    default -> System.out.println("  Unknown option.");
                }
            } catch (BookingException | IllegalArgumentException | DateTimeParseException e) {
                System.out.println("  ! " + (e instanceof DateTimeParseException
                        ? "Invalid date, use YYYY-MM-DD." : e.getMessage()));
            }
        }
    }

    private String ask(String prompt) {
        System.out.print(prompt);
        return in.hasNextLine() ? in.nextLine().trim() : "";
    }

    private RoomCategory askCategory() {
        String v = ask("Category [standard/deluxe/suite/any]: ").toUpperCase();
        try { return RoomCategory.valueOf(v); } catch (IllegalArgumentException e) { return null; }
    }

    private void printRooms(List<Room> rooms) {
        if (rooms.isEmpty()) System.out.println("No rooms available.");
        for (Room r : rooms)
            System.out.printf("  Room %s  %-9s $%7.2f/night  (sleeps %d)%n",
                    r.roomNo(), r.category().title(), r.rate(), r.category().capacity);
    }

    private void search() {
        LocalDate ci = LocalDate.parse(ask("Check-in (YYYY-MM-DD): "));
        LocalDate co = LocalDate.parse(ask("Check-out (YYYY-MM-DD): "));
        int n = Integer.parseInt(ask("Guests: "));
        printRooms(svc.search(ci, co, n, askCategory()));
    }

    private void book() {
        LocalDate ci = LocalDate.parse(ask("Check-in (YYYY-MM-DD): "));
        LocalDate co = LocalDate.parse(ask("Check-out (YYYY-MM-DD): "));
        int n = Integer.parseInt(ask("Guests: "));
        List<Room> rooms = svc.search(ci, co, n, askCategory());
        printRooms(rooms);
        if (rooms.isEmpty()) return;
        String roomNo = ask("Choose room number: ");
        Guest g = new Guest(ask("Name: "), ask("Email: "), ask("Phone: "));
        String card = ask("Card number (test: 4242 4242 4242 4242): ");
        Reservation r = svc.book(g, roomNo, ci, co, n, card);
        System.out.println("\nBooking confirmed!");
        details(r.resId);
    }

    private void details(String resId) {
        Reservation r = svc.find(resId);
        String line = "=".repeat(48);
        System.out.println(line);
        System.out.printf(" BOOKING %s   [%s]%n", r.resId, r.status);
        System.out.println(line);
        System.out.printf(" Guest    : %s <%s>%n", r.guest.name(), r.guest.email());
        System.out.printf(" Room     : %s (%s)%n", r.room.roomNo(), r.room.category().title());
        System.out.printf(" Stay     : %s -> %s (%d nights)%n", r.checkIn, r.checkOut, r.nights());
        System.out.printf(" Guests   : %d%n", r.guestCount);
        System.out.printf(" Rate     : $%.2f/night%n", r.room.rate());
        System.out.printf(" Total    : $%.2f%n", r.total);
        if (r.status == ReservationStatus.CANCELLED) System.out.printf(" Refunded : $%.2f%n", r.refund);
        System.out.println(" Payments :");
        for (Payment p : svc.paymentsFor(resId))
            System.out.printf("   %s  %s  %-14s %9.2f  %s%n",
                    p.createdAt(), p.payId(), p.method(), p.amount(), p.status());
        System.out.println(line);
    }

    private void myBookings() {
        List<Reservation> list = svc.myBookings(ask("Email: "));
        if (list.isEmpty()) System.out.println("No bookings found.");
        for (Reservation r : list)
            System.out.printf("  %s  Room %s  %s -> %s  $%.2f  %s%n",
                    r.resId, r.room.roomNo(), r.checkIn, r.checkOut, r.total, r.status);
    }
}
