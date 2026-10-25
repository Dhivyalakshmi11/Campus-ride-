import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.concurrent.Executors;

public class CampusRideServer {
    private static final int PORT = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
    private static final Path PUBLIC_DIR = Paths.get("public").toAbsolutePath().normalize();
    private static final RideService SERVICE = new RideService(Paths.get("data", "campusridestore.bin"));

    public static void main(String[] args) throws Exception {
        Files.createDirectories(PUBLIC_DIR);
        Files.createDirectories(Paths.get("data"));
        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);
        server.createContext("/api/", CampusRideServer::handleApi);
        server.createContext("/", CampusRideServer::serveStatic);
        server.setExecutor(Executors.newFixedThreadPool(8));
        server.start();
        System.out.println("CampusRide is running at http://localhost:" + PORT);
        System.out.println("Press Ctrl+C to stop the server.");
    }

    private static void handleApi(HttpExchange exchange) throws IOException {
        addCors(exchange);
        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(204, -1);
            return;
        }
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();
        try {
            Map<String, String> form = readForm(exchange);
            String response;
            int status = 200;

            if (path.equals("/api/register") && method.equals("POST")) {
                response = json(SERVICE.register(required(form, "name"), required(form, "role"),
                        required(form, "contact")));
            } else if (path.equals("/api/offer") && method.equals("POST")) {
                response = json(SERVICE.offerRide(required(form, "driver"), required(form, "vehicle"),
                        required(form, "vehicleType"), required(form, "pickup"),
                        required(form, "destination"), required(form, "departure"),
                        positiveInt(form, "seats")));
            } else if (path.equals("/api/request") && method.equals("POST")) {
                response = json(SERVICE.requestRide(required(form, "passenger"),
                        required(form, "pickup"), required(form, "destination"),
                        required(form, "departure"), positiveInt(form, "seats")));
            } else if (path.equals("/api/rides") && method.equals("GET")) {
                response = json(SERVICE.getRides());
            } else if (path.equals("/api/bookings") && method.equals("GET")) {
                response = json(SERVICE.getBookings());
            } else if (path.equals("/api/cancel-driver") && method.equals("POST")) {
                response = json(SERVICE.cancelDriverRide(required(form, "rideId"), required(form, "driver")));
            } else if (path.equals("/api/booking-status") && method.equals("POST")) {
                response = json(SERVICE.updateBookingStatus(required(form, "bookingId"), required(form, "status")));
            } else if (path.equals("/api/settings") && method.equals("POST")) {
                response = json(SERVICE.setTolerance(positiveOrZeroInt(form, "tolerance")));
            } else {
                status = 404;
                response = json(Map.of("ok", false, "message", "API endpoint not found."));
            }
            send(exchange, status, response, "application/json; charset=utf-8");
        } catch (IllegalArgumentException ex) {
            send(exchange, 400, json(Map.of("ok", false, "message", ex.getMessage())), "application/json; charset=utf-8");
        } catch (Exception ex) {
            ex.printStackTrace();
            send(exchange, 500, json(Map.of("ok", false, "message", "Server error. Check the VS Code terminal.")), "application/json; charset=utf-8");
        }
    }

    private static void serveStatic(HttpExchange exchange) throws IOException {
        String requested = exchange.getRequestURI().getPath();
        if (requested.equals("/")) requested = "/index.html";
        Path file = PUBLIC_DIR.resolve(requested.substring(1)).normalize();
        if (!file.startsWith(PUBLIC_DIR) || !Files.exists(file) || Files.isDirectory(file)) {
            send(exchange, 404, "File not found", "text/plain; charset=utf-8");
            return;
        }
        String type = "text/plain; charset=utf-8";
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".html")) type = "text/html; charset=utf-8";
        else if (name.endsWith(".css")) type = "text/css; charset=utf-8";
        else if (name.endsWith(".js")) type = "application/javascript; charset=utf-8";
        byte[] bytes = Files.readAllBytes(file);
        exchange.getResponseHeaders().set("Content-Type", type);
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) { out.write(bytes); }
    }

    private static Map<String, String> readForm(HttpExchange exchange) throws IOException {
        String raw;
        try (InputStream in = exchange.getRequestBody()) {
            raw = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        Map<String, String> result = new HashMap<>();
        if (!raw.isBlank()) {
            for (String pair : raw.split("&")) {
                String[] parts = pair.split("=", 2);
                String key = URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
                String value = parts.length > 1 ? URLDecoder.decode(parts[1], StandardCharsets.UTF_8) : "";
                result.put(key, value.trim());
            }
        }
        return result;
    }

    private static String required(Map<String, String> form, String key) {
        String value = form.getOrDefault(key, "").trim();
        if (value.isEmpty()) throw new IllegalArgumentException("Please enter " + key + ".");
        return value;
    }

    private static int positiveInt(Map<String, String> form, String key) {
        int value = positiveOrZeroInt(form, key);
        if (value < 1) throw new IllegalArgumentException(key + " must be at least 1.");
        return value;
    }

    private static int positiveOrZeroInt(Map<String, String> form, String key) {
        try {
            int value = Integer.parseInt(required(form, key));
            if (value < 0) throw new IllegalArgumentException(key + " cannot be negative.");
            return value;
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException(key + " must be a whole number.");
        }
    }

    private static void addCors(HttpExchange exchange) {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
    }

    private static void send(HttpExchange exchange, int status, String body, String contentType) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) { out.write(bytes); }
    }

    private static String json(Object value) {
        if (value == null) return "null";
        if (value instanceof String s) return "\"" + escape(s) + "\"";
        if (value instanceof Number || value instanceof Boolean) return value.toString();
        if (value instanceof Map<?, ?> map) {
            StringJoiner join = new StringJoiner(",", "{", "}");
            map.forEach((k, v) -> join.add(json(String.valueOf(k)) + ":" + json(v)));
            return join.toString();
        }
        if (value instanceof Iterable<?> items) {
            StringJoiner join = new StringJoiner(",", "[", "]");
            for (Object item : items) join.add(json(item));
            return join.toString();
        }
        return json(value.toString());
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    static class RideService {
        private final Path storePath;
        private Store store;

        RideService(Path storePath) {
            this.storePath = storePath;
            this.store = load();
        }

        private Store load() {
            if (Files.exists(storePath)) {
                try (ObjectInputStream in = new ObjectInputStream(Files.newInputStream(storePath))) {
                    return (Store) in.readObject();
                } catch (Exception ex) {
                    System.err.println("Could not read saved data; starting with an empty store.");
                }
            }
            return new Store();
        }

        private void save() {
            try {
                Files.createDirectories(storePath.getParent());
                try (ObjectOutputStream out = new ObjectOutputStream(Files.newOutputStream(storePath))) {
                    out.writeObject(store);
                }
            } catch (IOException ex) {
                throw new IllegalStateException("Could not save data: " + ex.getMessage());
            }
        }

        synchronized Map<String, Object> register(String name, String role, String contact) {
            String normalizedRole = role.toLowerCase(Locale.ROOT);
            if (!normalizedRole.equals("driver") && !normalizedRole.equals("passenger"))
                throw new IllegalArgumentException("Role must be driver or passenger.");
            boolean exists = store.users.stream().anyMatch(u -> u.name.equalsIgnoreCase(name) && u.role.equals(normalizedRole));
            if (exists) throw new IllegalArgumentException("This name is already registered for that role.");
            User user = new User("U" + (++store.userSeq), name, normalizedRole, contact);
            store.users.add(user);
            save();
            return result(true, "Registration successful. Your user ID is " + user.id + ".", "userId", user.id);
        }

        synchronized Map<String, Object> offerRide(String driver, String vehicle, String vehicleType,
                                                    String pickup, String destination, String departure, int seats) {
            checkTime(departure);
            if (destination.equalsIgnoreCase(pickup))
                throw new IllegalArgumentException("Pickup and destination must be different.");
            if (store.users.stream().noneMatch(u -> u.name.equalsIgnoreCase(driver) && u.role.equals("driver")))
                throw new IllegalArgumentException("Register this driver first using the Register section.");
            Vehicle registeredVehicle = VehicleFactory.create(vehicleType, vehicle, seats);
            Ride ride = new Ride("R" + (++store.rideSeq), driver, registeredVehicle.getDescription(),
                    registeredVehicle.getType(), pickup, destination, departure, seats, seats, "AVAILABLE");
            store.rides.add(ride);
            int autoMatched = matchPendingRequests(ride);
            save();
            return result(true, "Ride offered successfully. Ride ID: " + ride.id
                    + ". Pending requests matched: " + autoMatched, "rideId", ride.id,
                    "autoMatchedBookings", autoMatched);
        }

        synchronized Map<String, Object> requestRide(String passenger, String pickup, String destination,
                                                       String departure, int seats) {
            checkTime(departure);
            if (destination.equalsIgnoreCase(pickup))
                throw new IllegalArgumentException("Pickup and destination must be different.");
            if (store.users.stream().noneMatch(u -> u.name.equalsIgnoreCase(passenger) && u.role.equals("passenger")))
                throw new IllegalArgumentException("Register this passenger first using the Register section.");

            boolean duplicate = store.bookings.stream().anyMatch(b -> b.passenger.equalsIgnoreCase(passenger)
                    && b.pickup.equalsIgnoreCase(pickup) && b.destination.equalsIgnoreCase(destination)
                    && b.departure.equals(departure) && !b.status.equals("CANCELLED"));
            if (duplicate) throw new IllegalArgumentException("Duplicate request: you already requested this journey.");

            MatchingStrategy strategy = new RouteTimeSeatMatchingStrategy(store.toleranceMinutes);
            Ride match = store.rides.stream()
                    .filter(r -> r.status.equals("AVAILABLE") && strategy.matches(r, pickup, destination, departure, seats))
                    .min(Comparator.comparingLong(r -> timeDifference(r.departure, departure)))
                    .orElse(null);

            Booking booking = new Booking("B" + (++store.bookingSeq), passenger, pickup, destination, departure, seats);
            if (match == null) {
                booking.status = "PENDING";
                booking.rideId = "";
                store.bookings.add(booking);
                save();
                return result(true, "Request saved as pending. No compatible ride is available yet.", "bookingId", booking.id);
            }

            match.availableSeats -= seats;
            if (match.availableSeats < 0) { // Defensive guard against negative seats.
                match.availableSeats += seats;
                throw new IllegalStateException("Not enough seats available.");
            }
            if (match.availableSeats == 0) match.status = "FULL";
            booking.rideId = match.id;
            booking.status = "MATCHED";
            store.bookings.add(booking);
            save();
            return result(true, "Ride matched successfully with " + match.driver + ".", "bookingId", booking.id, "rideId", match.id);
        }

        // Matches older PENDING requests when a suitable new ride is published.
        private int matchPendingRequests(Ride ride) {
            if (!ride.status.equals("AVAILABLE")) return 0;
            int matched = 0;
            MatchingStrategy strategy = new RouteTimeSeatMatchingStrategy(store.toleranceMinutes);
            for (Booking booking : store.bookings) {
                if (!booking.status.equals("PENDING")) continue;
                if (ride.availableSeats < booking.seats) continue;
                if (!strategy.matches(ride, booking.pickup, booking.destination, booking.departure, booking.seats)) continue;
                ride.availableSeats -= booking.seats;
                if (ride.availableSeats == 0) ride.status = "FULL";
                booking.rideId = ride.id;
                booking.status = "MATCHED";
                booking.message = "Automatically matched when a compatible ride was published.";
                matched++;
                if (ride.status.equals("FULL")) break;
            }
            return matched;
        }

        synchronized Map<String, Object> cancelDriverRide(String rideId, String driver) {
            Ride ride = findRide(rideId);
            if (!ride.driver.equalsIgnoreCase(driver))
                throw new IllegalArgumentException("Only the registered driver can cancel this ride.");
            if (ride.status.equals("CANCELLED"))
                throw new IllegalArgumentException("This ride is already cancelled.");

            ride.status = "CANCELLED";
            int affected = 0;
            for (Booking booking : store.bookings) {
                if (booking.rideId.equals(ride.id) && (booking.status.equals("MATCHED") || booking.status.equals("PICKED_UP"))) {
                    booking.status = "CANCELLED";
                    booking.message = "Driver cancelled the ride. Please submit a new request.";
                    affected++;
                }
            }
            // The ride is cancelled, so seats are reset to a safe valid value.
            ride.availableSeats = ride.capacity;
            save();
            return result(true, "Ride cancelled. " + affected + " matched booking(s) updated.", "affectedBookings", affected);
        }

        synchronized Map<String, Object> updateBookingStatus(String bookingId, String status) {
            Booking booking = findBooking(bookingId);
            status = status.toUpperCase(Locale.ROOT);
            if (!Set.of("PICKED_UP", "COMPLETED", "CANCELLED").contains(status))
                throw new IllegalArgumentException("Status must be PICKED_UP, COMPLETED, or CANCELLED.");
            if (booking.status.equals("CANCELLED") || booking.status.equals("COMPLETED"))
                throw new IllegalArgumentException("This booking can no longer be updated.");
            if (status.equals("CANCELLED") && !booking.rideId.isEmpty()) {
                Ride ride = findRide(booking.rideId);
                if (!ride.status.equals("CANCELLED")) {
                    ride.availableSeats = Math.min(ride.capacity, ride.availableSeats + booking.seats);
                    if (ride.status.equals("FULL") && ride.availableSeats > 0) ride.status = "AVAILABLE";
                }
            }
            booking.status = status;
            save();
            return result(true, "Booking " + bookingId + " updated to " + status + ".", "bookingId", bookingId);
        }

        synchronized Map<String, Object> setTolerance(int minutes) {
            if (minutes > 720) throw new IllegalArgumentException("Tolerance cannot exceed 720 minutes.");
            store.toleranceMinutes = minutes;
            save();
            return result(true, "Matching time tolerance set to " + minutes + " minute(s).", "tolerance", minutes);
        }

        synchronized List<Map<String, Object>> getRides() {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Ride r : store.rides) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", r.id); m.put("driver", r.driver); m.put("vehicle", r.vehicle);
                m.put("vehicleType", r.vehicleType); m.put("pickup", r.pickup); m.put("destination", r.destination);
                m.put("departure", r.departure); m.put("capacity", r.capacity); m.put("availableSeats", r.availableSeats);
                m.put("status", r.status); out.add(m);
            }
            return out;
        }

        synchronized List<Map<String, Object>> getBookings() {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Booking b : store.bookings) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", b.id); m.put("passenger", b.passenger); m.put("pickup", b.pickup);
                m.put("destination", b.destination); m.put("departure", b.departure); m.put("seats", b.seats);
                m.put("rideId", b.rideId); m.put("status", b.status); m.put("message", b.message); out.add(m);
            }
            return out;
        }

        private Ride findRide(String id) {
            return store.rides.stream().filter(r -> r.id.equals(id)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Ride ID not found."));
        }

        private Booking findBooking(String id) {
            return store.bookings.stream().filter(b -> b.id.equals(id)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Booking ID not found."));
        }

        private boolean withinTolerance(String a, String b, int tolerance) {
            return timeDifference(a, b) <= tolerance;
        }

        private long timeDifference(String a, String b) {
            try {
                return Math.abs(java.time.Duration.between(LocalDateTime.parse(a), LocalDateTime.parse(b)).toMinutes());
            } catch (DateTimeParseException ex) {
                throw new IllegalArgumentException("Departure must be a valid date and time.");
            }
        }

        private void checkTime(String value) {
            try { LocalDateTime.parse(value); }
            catch (DateTimeParseException ex) { throw new IllegalArgumentException("Please choose a valid departure date and time."); }
        }

        private Map<String, Object> result(boolean ok, String message, Object... pairs) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ok", ok); m.put("message", message);
            for (int i = 0; i + 1 < pairs.length; i += 2) m.put(String.valueOf(pairs[i]), pairs[i + 1]);
            return m;
        }
    }

    // Vehicle inheritance: each ride uses a concrete vehicle type created by the factory.
    interface Vehicle {
        String getType();
        String getDescription();
        int getSeatCapacity();
    }

    static abstract class AbstractVehicle implements Vehicle {
        private final String description;
        private final int seatCapacity;
        AbstractVehicle(String description, int seatCapacity) {
            this.description = description;
            this.seatCapacity = seatCapacity;
        }
        public String getDescription() { return description; }
        public int getSeatCapacity() { return seatCapacity; }
    }

    static class Car extends AbstractVehicle {
        Car(String description, int seats) { super(description, seats); }
        public String getType() { return "Car"; }
    }
    static class Van extends AbstractVehicle {
        Van(String description, int seats) { super(description, seats); }
        public String getType() { return "Van"; }
    }
    static class Bike extends AbstractVehicle {
        Bike(String description, int seats) { super(description, seats); }
        public String getType() { return "Bike"; }
    }
    static class OtherVehicle extends AbstractVehicle {
        OtherVehicle(String description, int seats) { super(description, seats); }
        public String getType() { return "Other"; }
    }

    static class VehicleFactory {
        static Vehicle create(String type, String description, int seats) {
            if (seats < 1) throw new IllegalArgumentException("Vehicle seats must be at least 1.");
            return switch (type.trim().toLowerCase(Locale.ROOT)) {
                case "car" -> new Car(description, seats);
                case "van" -> new Van(description, seats);
                case "bike" -> new Bike(description, seats);
                case "other" -> new OtherVehicle(description, seats);
                default -> throw new IllegalArgumentException("Vehicle type must be Car, Van, Bike, or Other.");
            };
        }
    }

    // Matching interface allows the matching rules to be changed without rewriting RideService.
    interface MatchingStrategy {
        boolean matches(Ride ride, String pickup, String destination, String departure, int seats);
    }

    static class RouteTimeSeatMatchingStrategy implements MatchingStrategy {
        private final int toleranceMinutes;
        RouteTimeSeatMatchingStrategy(int toleranceMinutes) { this.toleranceMinutes = toleranceMinutes; }
        public boolean matches(Ride ride, String pickup, String destination, String departure, int seats) {
            return ride.status.equals("AVAILABLE")
                    && ride.availableSeats >= seats
                    && ride.pickup.equalsIgnoreCase(pickup)
                    && ride.destination.equalsIgnoreCase(destination)
                    && Math.abs(java.time.Duration.between(LocalDateTime.parse(ride.departure),
                        LocalDateTime.parse(departure)).toMinutes()) <= toleranceMinutes;
        }
    }

    static class Store implements Serializable {
        private static final long serialVersionUID = 1L;
        List<User> users = new ArrayList<>();
        List<Ride> rides = new ArrayList<>();
        List<Booking> bookings = new ArrayList<>();
        int userSeq, rideSeq, bookingSeq;
        int toleranceMinutes = 60;
    }

    static class User implements Serializable {
        private static final long serialVersionUID = 1L;
        String id, name, role, contact;
        User(String id, String name, String role, String contact) {
            this.id=id; this.name=name; this.role=role; this.contact=contact;
        }
    }

    static class Ride implements Serializable {
        private static final long serialVersionUID = 1L;
        String id, driver, vehicle, vehicleType, pickup, destination, departure, status;
        int capacity, availableSeats;
        Ride(String id, String driver, String vehicle, String vehicleType, String pickup, String destination,
             String departure, int capacity, int availableSeats, String status) {
            this.id=id; this.driver=driver; this.vehicle=vehicle; this.vehicleType=vehicleType;
            this.pickup=pickup; this.destination=destination; this.departure=departure;
            this.capacity=capacity; this.availableSeats=availableSeats; this.status=status;
        }
    }

    static class Booking implements Serializable {
        private static final long serialVersionUID = 1L;
        String id, passenger, pickup, destination, departure, rideId="", status="PENDING", message="";
        int seats;
        Booking(String id, String passenger, String pickup, String destination, String departure, int seats) {
            this.id=id; this.passenger=passenger; this.pickup=pickup; this.destination=destination;
            this.departure=departure; this.seats=seats;
        }
    }
}
