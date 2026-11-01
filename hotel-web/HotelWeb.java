/*
 * HotelWeb - web front end for the hotel reservation system.
 * Uses only the JDK's built-in HTTP server (no frameworks, no dependencies).
 * Reuses HotelService / FileStore / PaymentGateway from HotelSystem.java.
 *
 * Environment variables
 *   PORT        port to listen on            (default 8080)
 *   DATA_DIR    folder for the CSV data      (default ./hotel_data)
 *   PUBLIC_DIR  folder containing index.html (default ./public)
 *
 * Endpoints
 *   GET  /                 the web page
 *   GET  /health           health check
 *   GET  /api/search       checkIn, checkOut, guests, [category]
 *   POST /api/book         roomNo, checkIn, checkOut, guests, name, email, [phone], card
 *   POST /api/booking      id, email                      (view a booking)
 *   POST /api/cancel       id, email
 */
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;

public class HotelWeb {

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
        Path data = Paths.get(System.getenv().getOrDefault("DATA_DIR", "hotel_data"));
        Path pub = Paths.get(System.getenv().getOrDefault("PUBLIC_DIR", "public"));

        HotelService svc = new HotelService(new FileStore(data));
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/api/", ex -> api(ex, svc));
        server.createContext("/", ex -> page(ex, pub));
        server.setExecutor(Executors.newFixedThreadPool(8));
        server.start();
        System.out.println("Hotel site running on http://localhost:" + port + "  (data: " + data.toAbsolutePath() + ")");
    }

    // ------------------------------------------------------------------ routing
    private static void api(HttpExchange ex, HotelService svc) throws IOException {
        try {
            Map<String, String> p = params(ex);
            String route = ex.getRequestMethod() + " " + ex.getRequestURI().getPath();
            String out;
            synchronized (svc) {   // same monitor HotelService uses: consistent reads
                switch (route) {
                    case "GET /api/search" -> out = search(svc, p);
                    case "POST /api/book" -> out = book(svc, p);
                    case "POST /api/booking" -> out = reservationJson(svc, owned(svc, p));
                    case "POST /api/cancel" -> {
                        Reservation r = owned(svc, p);
                        svc.cancel(r.resId);
                        out = reservationJson(svc, r);
                    }
                    default -> {
                        send(ex, 404, "application/json", "{\"error\":\"Not found.\"}");
                        return;
                    }
                }
            }
            send(ex, 200, "application/json", out);
        } catch (BookingException e) {
            send(ex, 400, "application/json", "{\"error\":" + q(e.getMessage()) + "}");
        } catch (IllegalArgumentException | DateTimeException e) {
            send(ex, 400, "application/json",
                    "{\"error\":\"Check the dates and numbers you entered and try again.\"}");
        } catch (Exception e) {
            e.printStackTrace();
            send(ex, 500, "application/json", "{\"error\":\"Something went wrong on our side.\"}");
        }
    }

    private static void page(HttpExchange ex, Path pub) throws IOException {
        String path = ex.getRequestURI().getPath();
        if (path.equals("/health")) { send(ex, 200, "text/plain", "ok"); return; }
        if (!path.equals("/") && !path.equals("/index.html")) { send(ex, 404, "text/plain", "Not found"); return; }
        if (!ex.getRequestMethod().equals("GET")) { send(ex, 405, "text/plain", "Method not allowed"); return; }
        ex.getResponseHeaders().set("Content-Security-Policy",
                "default-src 'self'; style-src 'self' 'unsafe-inline'; script-src 'self' 'unsafe-inline'");
        ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        ex.getResponseHeaders().set("Referrer-Policy", "no-referrer");
        send(ex, 200, "text/html; charset=utf-8", Files.readString(pub.resolve("index.html")));
    }

    // ----------------------------------------------------------------- handlers
    private static String search(HotelService svc, Map<String, String> p) {
        LocalDate in = LocalDate.parse(req(p, "checkIn"));
        LocalDate out = LocalDate.parse(req(p, "checkOut"));
        int guests = Integer.parseInt(req(p, "guests"));
        if (guests < 1) throw new BookingException("At least one guest is required.");

        List<Room> rooms = svc.search(in, out, guests, category(p.get("category")));
        long nights = ChronoUnit.DAYS.between(in, out);

        StringBuilder sb = new StringBuilder("{\"nights\":").append(nights).append(",\"rooms\":[");
        for (int i = 0; i < rooms.size(); i++) {
            Room r = rooms.get(i);
            if (i > 0) sb.append(',');
            sb.append("{\"roomNo\":").append(q(r.roomNo()))
              .append(",\"category\":").append(q(r.category().title()))
              .append(",\"rate\":").append(num(r.rate()))
              .append(",\"capacity\":").append(r.category().capacity)
              .append(",\"total\":").append(num(r.rate() * nights)).append('}');
        }
        return sb.append("]}").toString();
    }

    private static String book(HotelService svc, Map<String, String> p) {
        Guest g = new Guest(req(p, "name").trim(), req(p, "email").trim(), p.getOrDefault("phone", "").trim());
        Reservation r = svc.book(g, req(p, "roomNo"),
                LocalDate.parse(req(p, "checkIn")), LocalDate.parse(req(p, "checkOut")),
                Integer.parseInt(req(p, "guests")), req(p, "card"));
        return reservationJson(svc, r);
    }

    /** A booking is only visible to someone who knows both its ID and the guest's email. */
    private static Reservation owned(HotelService svc, Map<String, String> p) {
        BookingException noMatch = new BookingException("No booking matches that ID and email.");
        Reservation r;
        try {
            r = svc.find(req(p, "id").trim());
        } catch (BookingException e) {
            throw noMatch;
        }
        if (!r.guest.email().equalsIgnoreCase(req(p, "email").trim())) throw noMatch;
        return r;
    }

    // --------------------------------------------------------------------- JSON
    private static String reservationJson(HotelService svc, Reservation r) {
        boolean canCancel = r.status == ReservationStatus.CONFIRMED && r.checkOut.isAfter(LocalDate.now());
        double refundIfCancelled = canCancel ? HotelService.refundAmount(r, LocalDate.now()) : 0.0;

        StringBuilder sb = new StringBuilder("{");
        sb.append("\"id\":").append(q(r.resId));
        sb.append(",\"status\":").append(q(r.status.name()));
        sb.append(",\"guest\":{\"name\":").append(q(r.guest.name()))
          .append(",\"email\":").append(q(r.guest.email()))
          .append(",\"phone\":").append(q(r.guest.phone())).append('}');
        sb.append(",\"room\":{\"no\":").append(q(r.room.roomNo()))
          .append(",\"category\":").append(q(r.room.category().title()))
          .append(",\"rate\":").append(num(r.room.rate())).append('}');
        sb.append(",\"checkIn\":").append(q(r.checkIn.toString()));
        sb.append(",\"checkOut\":").append(q(r.checkOut.toString()));
        sb.append(",\"nights\":").append(r.nights());
        sb.append(",\"guests\":").append(r.guestCount);
        sb.append(",\"total\":").append(num(r.total));
        sb.append(",\"refund\":").append(num(r.refund));
        sb.append(",\"canCancel\":").append(canCancel);
        sb.append(",\"refundIfCancelled\":").append(num(refundIfCancelled));
        sb.append(",\"payments\":[");
        List<Payment> pays = svc.paymentsFor(r.resId);
        for (int i = 0; i < pays.size(); i++) {
            Payment pay = pays.get(i);
            if (i > 0) sb.append(',');
            sb.append("{\"ref\":").append(q(pay.payId()))
              .append(",\"method\":").append(q(pay.method()))
              .append(",\"amount\":").append(num(pay.amount()))
              .append(",\"status\":").append(q(pay.status()))
              .append(",\"at\":").append(q(pay.createdAt())).append('}');
        }
        return sb.append("]}").toString();
    }

    private static String num(double d) { return String.format(Locale.ROOT, "%.2f", d); }

    /** JSON string literal with escaping. */
    private static String q(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> { if (c < 0x20) sb.append(String.format("\\u%04x", (int) c)); else sb.append(c); }
            }
        }
        return sb.append('"').toString();
    }

    // ------------------------------------------------------------------ helpers
    private static RoomCategory category(String v) {
        if (v == null || v.isBlank() || v.equalsIgnoreCase("any")) return null;
        return RoomCategory.valueOf(v.trim().toUpperCase());
    }

    private static String req(Map<String, String> p, String key) {
        String v = p.get(key);
        if (v == null || v.isBlank()) throw new BookingException("Missing field: " + key + ".");
        if (v.length() > 200) throw new BookingException("The " + key + " field is too long.");
        return v;
    }

    /** Query string for GET, form-encoded body for POST. */
    private static Map<String, String> params(HttpExchange ex) throws IOException {
        String raw = ex.getRequestMethod().equals("GET")
                ? ex.getRequestURI().getRawQuery()
                : readBody(ex);
        Map<String, String> map = new HashMap<>();
        if (raw == null || raw.isEmpty()) return map;
        for (String pair : raw.split("&")) {
            int i = pair.indexOf('=');
            String k = i < 0 ? pair : pair.substring(0, i);
            String v = i < 0 ? "" : pair.substring(i + 1);
            map.put(URLDecoder.decode(k, StandardCharsets.UTF_8), URLDecoder.decode(v, StandardCharsets.UTF_8));
        }
        return map;
    }

    private static String readBody(HttpExchange ex) throws IOException {
        try (InputStream in = ex.getRequestBody()) {
            byte[] b = in.readNBytes(16 * 1024 + 1);
            if (b.length > 16 * 1024) throw new BookingException("Request too large.");
            return new String(b, StandardCharsets.UTF_8);
        }
    }

    private static void send(HttpExchange ex, int status, String type, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", type.contains("charset") ? type : type + "; charset=utf-8");
        if (type.startsWith("application/json")) ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(status, bytes.length);
        try (var os = ex.getResponseBody()) { os.write(bytes); }
    }
}
