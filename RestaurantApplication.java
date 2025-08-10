package com.example.smartrestaurant;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

@SpringBootApplication
public class RestaurantApplication {

    public static void main(String[] args) {
        SpringApplication.run(RestaurantApplication.class, args);
    }

    @Bean
    CommandLineRunner initData(MenuItemRepository menuRepo) {
        return args -> {
            if (menuRepo.count() == 0) {
                menuRepo.saveAll(List.of(
                    new MenuItem("Margherita Pizza", "Classic cheese & tomato", 7.99, "pizza"),
                    new MenuItem("BBQ Chicken Pizza", "Chicken, BBQ sauce, cheese", 9.99, "pizza"),
                    new MenuItem("Veggie Burger", "Patty with fresh veggies", 6.49, "burger"),
                    new MenuItem("Cheeseburger", "Beef patty with cheddar", 7.49, "burger"),
                    new MenuItem("French Fries", "Crispy golden fries", 2.99, "sides"),
                    new MenuItem("Caesar Salad", "Crisp romaine & Caesar dressing", 4.99, "salad")
                ));
            }
        };
    }

    // -------------------------
    // Entities
    // -------------------------
    @Entity
    @Table(name = "menu_items")
    public static class MenuItem {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;
        private String name;
        private String description;
        private double price;
        private String category;
        protected MenuItem() {}
        public MenuItem(String name, String description, double price, String category) {
            this.name = name; this.description = description; this.price = price; this.category = category;
        }
        public Long getId() { return id; }
        public String getName() { return name; }
        public String getDescription() { return description; }
        public double getPrice() { return price; }
        public String getCategory() { return category; }
    }

    @Entity
    @Table(name = "orders")
    public static class FoodOrder {
        @Id
        private String id;
        private Instant createdAt;
        private String customerName;
        private String customerPhone;
        private double totalAmount;
        @Enumerated(EnumType.STRING)
        private Status status;
        private Double deliveryLat;
        private Double deliveryLng;
        private Long etaSeconds;

        @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
        @JoinColumn(name = "order_id")
        private List<OrderItem> items = new ArrayList<>();

        protected FoodOrder() {}
        public FoodOrder(String id, String customerName, String customerPhone, List<OrderItem> items, double totalAmount) {
            this.id = id; this.customerName = customerName; this.customerPhone = customerPhone;
            this.items = items; this.totalAmount = totalAmount; this.createdAt = Instant.now(); this.status = Status.RECEIVED;
        }
        public enum Status { RECEIVED, PREPARING, READY, OUT_FOR_DELIVERY, DELIVERED, CANCELLED }
        public String getId() { return id; }
        public Instant getCreatedAt() { return createdAt; }
        public String getCustomerName() { return customerName; }
        public String getCustomerPhone() { return customerPhone; }
        public List<OrderItem> getItems() { return items; }
        public double getTotalAmount() { return totalAmount; }
        public Status getStatus() { return status; }
        public void setStatus(Status s) { this.status = s; }
        public Double getDeliveryLat() { return deliveryLat; }
        public Double getDeliveryLng() { return deliveryLng; }
        public void setDeliveryLat(Double deliveryLat) { this.deliveryLat = deliveryLat; }
        public void setDeliveryLng(Double deliveryLng) { this.deliveryLng = deliveryLng; }
        public Long getEtaSeconds() { return etaSeconds; }
        public void setEtaSeconds(Long etaSeconds) { this.etaSeconds = etaSeconds; }
    }

    @Entity
    @Table(name = "order_items")
    public static class OrderItem {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;
        private Long menuItemId;
        private String name;
        private int quantity;
        private double unitPrice;
        protected OrderItem() {}
        public OrderItem(Long menuItemId, String name, int quantity, double unitPrice) {
            this.menuItemId = menuItemId; this.name = name; this.quantity = quantity; this.unitPrice = unitPrice;
        }
        public Long getId() { return id; }
        public Long getMenuItemId() { return menuItemId; }
        public String getName() { return name; }
        public int getQuantity() { return quantity; }
        public double getUnitPrice() { return unitPrice; }
    }

    // -------------------------
    // Repos
    // -------------------------
    public interface MenuItemRepository extends JpaRepository<MenuItem, Long> {
        List<MenuItem> findByCategory(String category);
    }
    public interface OrderRepository extends JpaRepository<FoodOrder, String> {
        @Query("select o from RestaurantApplication$FoodOrder o where o.status = ?1")
        List<FoodOrder> findByStatus(FoodOrder.Status status);
    }

    // -------------------------
    // Controller
    // -------------------------
    @RestController
    @RequestMapping("/api")
    public static class ApiController {
        private final MenuItemRepository menuRepo;
        private final OrderService orderService;
        private final PaymentService paymentService;
        private final LocationService locationService;
        public ApiController(MenuItemRepository menuRepo, OrderService orderService, PaymentService paymentService, LocationService locationService) {
            this.menuRepo = menuRepo; this.orderService = orderService; this.paymentService = paymentService; this.locationService = locationService;
        }

        @GetMapping("/menu")
        public List<MenuItem> getMenu(@RequestParam(required = false) String category) {
            if (category == null || category.isBlank()) return menuRepo.findAll();
            return menuRepo.findByCategory(category);
        }

        @PostMapping("/orders")
        public ResponseEntity<Map<String,Object>> placeOrder(@RequestBody PlaceOrderRequest req) {
            try {
                FoodOrder order = orderService.placeOrder(req);
                Map<String,Object> resp = new HashMap<>();
                resp.put("orderId", order.getId());
                resp.put("status", order.getStatus());
                resp.put("total", order.getTotalAmount());
                resp.put("paymentToken", paymentService.createPaymentIntent(order)); // simulated token
                return ResponseEntity.ok(resp);
            } catch (IllegalArgumentException ex) {
                return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
            }
        }

        @GetMapping("/orders/{id}")
        public ResponseEntity<FoodOrder> getOrder(@PathVariable String id) {
            return orderService.findById(id).map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
        }

        @PostMapping("/orders/{id}/pay")
        public ResponseEntity<Map<String,Object>> payOrder(@PathVariable String id, @RequestBody Map<String,String> body) {
            Optional<FoodOrder> maybe = orderService.findById(id);
            if (maybe.isEmpty()) return ResponseEntity.notFound().build();
            FoodOrder order = maybe.get();
            boolean paid = paymentService.capturePayment(order, body.getOrDefault("paymentToken",""));
            if (paid) {
                orderService.markPaid(order.getId());
                return ResponseEntity.ok(Map.of("status","PAID"));
            } else {
                return ResponseEntity.badRequest().body(Map.of("error","Payment failed"));
            }
        }

        @PostMapping("/orders/{id}/track")
        public ResponseEntity<Map<String,Object>> setDeliveryLocation(@PathVariable String id, @RequestBody Map<String, Double> coords) {
            Optional<FoodOrder> maybe = orderService.findById(id);
            if (maybe.isEmpty()) return ResponseEntity.notFound().build();
            FoodOrder order = maybe.get();
            Double lat = coords.get("lat"); Double lng = coords.get("lng");
            if (lat == null || lng == null) return ResponseEntity.badRequest().body(Map.of("error","Missing lat/lng"));
            order.setDeliveryLat(lat); order.setDeliveryLng(lng);
            long eta = locationService.estimateEtaSeconds(lat,lng);
            order.setEtaSeconds(eta);
            orderService.save(order);
            return ResponseEntity.ok(Map.of("etaSeconds", eta));
        }

        @GetMapping(path="/orders/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
        public SseEmitter streamOrderEvents(@PathVariable String id) {
            return orderService.createEmitterForOrder(id);
        }
    }

    // DTO
    public static class PlaceOrderRequest {
        public String customerName;
        public String customerPhone;
        public List<ItemRequest> items;
        public static class ItemRequest { public Long menuItemId; public int quantity; }
    }

    // -------------------------
    // OrderService with Kitchen Worker
    // -------------------------
    @org.springframework.stereotype.Service
    public static class OrderService {
        private final MenuItemRepository menuRepo;
        private final OrderRepository orderRepo;
        private final ExecutorService kitchenPool;
        private final BlockingQueue<String> preparationQueue;
        private final Map<String,SseEmitter> emitters = new ConcurrentHashMap<>();
        private final AtomicLong seq = new AtomicLong();

        public OrderService(MenuItemRepository menuRepo, OrderRepository orderRepo) {
            this.menuRepo = menuRepo; this.orderRepo = orderRepo;
            this.preparationQueue = new LinkedBlockingQueue<>(5000);
            this.kitchenPool = Executors.newFixedThreadPool(Math.max(2, Runtime.getRuntime().availableProcessors()/2));
            for (int i=0; i<Math.max(1, Runtime.getRuntime().availableProcessors()/2); i++) {
                kitchenPool.submit(this::kitchenWorker);
            }
        }

        @Transactional
        public FoodOrder placeOrder(PlaceOrderRequest req) {
            if (req == null || req.items == null || req.items.isEmpty()) throw new IllegalArgumentException("Empty order");
            if (req.customerName == null || req.customerName.isBlank()) throw new IllegalArgumentException("Missing customerName");
            Map<Long,Integer> counts = new HashMap<>();
            for (var it: req.items) {
                if (it.quantity <= 0) throw new IllegalArgumentException("Quantity must be > 0");
                counts.merge(it.menuItemId, it.quantity, Integer::sum);
            }
            List<MenuItem> menuItems = menuRepo.findAllById(counts.keySet());
            if (menuItems.size() != counts.size()) throw new IllegalArgumentException("Invalid menu item id included");
            List<OrderItem> orderItems = new ArrayList<>(counts.size());
            double total = 0.0;
            for (MenuItem mi : menuItems) {
                int q = counts.get(mi.getId());
                orderItems.add(new OrderItem(mi.getId(), mi.getName(), q, mi.getPrice()));
                total += mi.getPrice() * q;
            }
            String id = generateOrderId();
            FoodOrder order = new FoodOrder(id, req.customerName, req.customerPhone, orderItems, round2(total));
            orderRepo.save(order);
            preparationQueue.offer(order.getId());
            return order;
        }

        public Optional<FoodOrder> findById(String id) { return orderRepo.findById(id); }
        public void save(FoodOrder o) { orderRepo.save(o); }

        private String generateOrderId() {
            long s = seq.incrementAndGet();
            long ts = LocalDateTime.now().toEpochSecond(ZoneOffset.UTC);
            return "ORD" + ts + "-" + s;
        }

        private void kitchenWorker() {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    String id = preparationQueue.poll(1, TimeUnit.SECONDS);
                    if (id == null) continue;
                    Optional<FoodOrder> maybe = orderRepo.findById(id);
                    if (maybe.isEmpty()) continue;
                    FoodOrder order = maybe.get();
                    if (order.getStatus() != FoodOrder.Status.RECEIVED) continue;
                    updateStatus(order, FoodOrder.Status.PREPARING);
                    int totalUnits = order.getItems().stream().mapToInt(OrderItem::getQuantity).sum();
                    long millis = Math.min(60000, Math.max(4000, totalUnits * 3000L));
                    Thread.sleep(millis);
                    updateStatus(order, FoodOrder.Status.READY);
                    updateStatus(order, FoodOrder.Status.OUT_FOR_DELIVERY);
                    long deliveryMillis = (order.getEtaSeconds() != null ? order.getEtaSeconds()*1000L : 15000L);
                    Thread.sleep(Math.min(45000, Math.max(10000, deliveryMillis)));
                    updateStatus(order, FoodOrder.Status.DELIVERED);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }

        private void updateStatus(FoodOrder order, FoodOrder.Status newStatus) {
            order.setStatus(newStatus);
            orderRepo.save(order);
            notifyClients(order);
        }

        public void markPaid(String id) {
            findById(id).ifPresent(order -> orderRepo.save(order));
        }

        public SseEmitter createEmitterForOrder(String orderId) {
            SseEmitter emitter = new SseEmitter(0L);
            emitters.put(orderId, emitter);
            Optional<FoodOrder> maybe = orderRepo.findById(orderId);
            maybe.ifPresent(order -> {
                try { emitter.send(SseEmitter.event().name("init").data(order.getStatus().name())); } catch (Exception ignored) {}
            });
            emitter.onCompletion(() -> emitters.remove(orderId));
            emitter.onTimeout(() -> emitters.remove(orderId));
            return emitter;
        }

        private void notifyClients(FoodOrder order) {
            SseEmitter emitter = emitters.get(order.getId());
            if (emitter != null) {
                try {
                    emitter.send(SseEmitter.event().name("status").data(Map.of(
                            "status", order.getStatus().name(),
                            "orderId", order.getId(),
                            "timestamp", Instant.now().toString()
                    )));
                    if (order.getStatus() == FoodOrder.Status.READY) {
                        emitter.send(SseEmitter.event().name("notification").data("Your order is ready!"));
                    }
                    if (order.getStatus() == FoodOrder.Status.DELIVERED) emitter.complete();
                } catch (Exception e) {
                    emitter.completeWithError(e);
                    emitters.remove(order.getId());
                }
            }
        }

        private static double round2(double v) { return Math.round(v * 100.0) / 100.0; }
    }

    // -------------------------
    // PaymentService with WebClient placeholders
    // -------------------------
    @org.springframework.stereotype.Service
    public static class PaymentService {
        private final WebClient webClient;
        @Value("${stripe.api.key:}")
        private String stripeKey;
        @Value("${razorpay.key:}")
        private String razorKey;
        @Value("${razorpay.secret:}")
        private String razorSecret;

        public PaymentService(WebClient.Builder builder) {
            this.webClient = builder.build();
        }

        public String createPaymentIntent(FoodOrder order) {
            // Here we return a simulated token. Replace with Stripe/Razorpay API calls:
            // Example (Stripe): create PaymentIntent via Stripe API using secret key
            return "SIMULATED_PAYMENT_TOKEN_" + order.getId();
        }

        public boolean capturePayment(FoodOrder order, String token) {
            if (token == null || token.isBlank()) return false;
            return Math.random() > 0.02;
        }
    }

    // -------------------------
    // LocationService using Haversine + WebClient example
    // -------------------------
    @org.springframework.stereotype.Service
    public static class LocationService {
        private final WebClient webClient;
        @Value("${google.maps.key:}")
        private String gmapsKey;
        private static final double REST_LAT = 12.9719;
        private static final double REST_LNG = 77.5946;

        public LocationService(WebClient.Builder builder) {
            this.webClient = builder.build();
        }

        public long estimateEtaSeconds(double destLat, double destLng) {
            double km = haversineKm(REST_LAT, REST_LNG, destLat, destLng);
            double secs = Math.max(300, Math.min(3600, km * 120));
            return (long) Math.round(secs);
        }

        // Example of how you'd call Google Maps Directions API (synchronous example using WebClient):
        public Optional<Long> estimateEtaFromGoogle(double destLat, double destLng) {
            if (gmapsKey == null || gmapsKey.isBlank()) return Optional.empty();
            try {
                // Build directions API URL (replace with proper encoded parameters)
                String url = "https://maps.googleapis.com/maps/api/directions/json?origin="
                        + REST_LAT + "," + REST_LNG + "&destination=" + destLat + "," + destLng + "&key=" + gmapsKey;
                Map result = webClient.get().uri(url).retrieve().bodyToMono(Map.class).block();
                if (result == null) return Optional.empty();
                // Parsing is left as an exercise: extract duration.value in seconds from routes[0].legs[0].duration.value
                return Optional.empty();
            } catch (Exception e) {
                return Optional.empty();
            }
        }

        private static double haversineKm(double lat1, double lon1, double lat2, double lon2) {
            double R = 6371.0;
            double dLat = Math.toRadians(lat2 - lat1);
            double dLon = Math.toRadians(lon2 - lon1);
            double a = Math.sin(dLat/2) * Math.sin(dLat/2) +
                       Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                       Math.sin(dLon/2) * Math.sin(dLon/2);
            double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1-a));
            return R * c;
        }
    }
}
