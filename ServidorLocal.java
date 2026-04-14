import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;

public class ServidorLocal {
    
    // 🌟 EL INTERRUPTOR MÁGICO 🌟
    // Cambia esto a 'true' cuando quieras usar la nube (Render/Supabase)
    // Cambia esto a 'false' para usar tu propia computadora (pgAdmin)
    private static final boolean MODO_NUBE = false; 

    // --- CREDENCIALES LOCALES (Tu PC) ---
    private static final String LOCAL_URL = "jdbc:postgresql://localhost:5432/einventario_local";
    private static final String LOCAL_USER = "postgres";
    private static final String LOCAL_PASS = "brandon1600";

    // --- CREDENCIALES NUBE (Supabase) ---
    private static final String NUBE_URL = "jdbc:postgresql://aws-1-us-east-1.pooler.supabase.com:6543/postgres?sslmode=require";
    private static final String NUBE_USER = "postgres.yrmxmdjmsvsnqsnekjkf"; 
    private static final String NUBE_PASS = "DSozr97Fl1fPFxPY";

    // Esta función decide automáticamente a dónde conectarse
    private static Connection getConexion() throws Exception {
        if (MODO_NUBE) {
            return DriverManager.getConnection(NUBE_URL, NUBE_USER, NUBE_PASS);
        } else {
            return DriverManager.getConnection(LOCAL_URL, LOCAL_USER, LOCAL_PASS);
        }
    }

    public static void main(String[] args) throws Exception {
        // Imprimir en qué modo estamos trabajando
        System.out.println("Iniciando en modo: " + (MODO_NUBE ? "☁️ NUBE (Supabase)" : "💻 LOCAL (pgAdmin)"));

        // Preparar la base de datos
        try (Connection conn = getConexion(); Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE IF NOT EXISTS producto (sku VARCHAR(50) PRIMARY KEY, nombre VARCHAR(255) NOT NULL, stock INT NOT NULL);");
            stmt.execute("CREATE TABLE IF NOT EXISTS kardex (id SERIAL PRIMARY KEY, sku VARCHAR(50) REFERENCES producto(sku), tipo VARCHAR(20) NOT NULL, cantidad INT NOT NULL, fecha TIMESTAMP DEFAULT CURRENT_TIMESTAMP);");
            System.out.println("✅ Base de datos lista. Tablas verificadas.");
        } catch (Exception e) {
            System.err.println("❌ Error conectando a PostgreSQL. ¿Pusiste bien la contraseña o encendiste pgAdmin?");
            e.printStackTrace();
            return; 
        }

        // Definir puerto (Render usa uno dinámico, tu PC usa 8080)
        String portStr = System.getenv("PORT");
        int port = (portStr != null) ? Integer.parseInt(portStr) : 8080;

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/api/productos", new ListarHandler());
        server.createContext("/api/crear", new CrearHandler());
        server.createContext("/api/movimiento", new MovimientoHandler());
        server.createContext("/api/kardex", new KardexHandler());
        
        server.setExecutor(null);
        server.start();
        System.out.println("🚀 Servidor corriendo en el puerto: " + port);
    }

    // --- MANEJADORES DE RUTAS ---

    static class ListarHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange t) throws IOException {
            agregarCabecerasCORS(t);
            if ("OPTIONS".equals(t.getRequestMethod())) { t.sendResponseHeaders(204, -1); return; }
            StringBuilder jsonArray = new StringBuilder("[");
            String sql = "SELECT sku, nombre, stock FROM producto ORDER BY nombre ASC";
            try (Connection conn = getConexion(); PreparedStatement pstmt = conn.prepareStatement(sql); ResultSet rs = pstmt.executeQuery()) {
                boolean primero = true;
                while (rs.next()) {
                    if (!primero) jsonArray.append(",");
                    jsonArray.append(String.format("{\"sku\":\"%s\", \"nombre\":\"%s\", \"stock\":%d}", rs.getString("sku"), rs.getString("nombre").replace("\"", "\\\""), rs.getInt("stock")));
                    primero = false;
                }
            } catch (Exception e) { e.printStackTrace(); }
            jsonArray.append("]");
            enviarRespuesta(t, 200, jsonArray.toString());
        }
    }

    static class CrearHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange t) throws IOException {
            agregarCabecerasCORS(t);
            if ("OPTIONS".equals(t.getRequestMethod())) { t.sendResponseHeaders(204, -1); return; }
            try {
                Map<String, String> params = obtenerParametros(t.getRequestURI().getQuery());
                String sql = "INSERT INTO producto (sku, nombre, stock) VALUES (?, ?, ?)";
                try (Connection conn = getConexion(); PreparedStatement pstmt = conn.prepareStatement(sql)) {
                    pstmt.setString(1, params.get("sku")); pstmt.setString(2, params.get("nombre")); pstmt.setInt(3, Integer.parseInt(params.get("stock")));
                    pstmt.executeUpdate();
                }
                enviarRespuesta(t, 200, "{\"mensaje\": \"Producto creado\"}");
            } catch (Exception e) { enviarRespuesta(t, 400, "{\"error\": \"Error al crear producto.\"}"); }
        }
    }

    static class MovimientoHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange t) throws IOException {
            agregarCabecerasCORS(t);
            if ("OPTIONS".equals(t.getRequestMethod())) { t.sendResponseHeaders(204, -1); return; }
            try {
                Map<String, String> params = obtenerParametros(t.getRequestURI().getQuery());
                String sku = params.get("sku"), tipo = params.get("tipo"); int cantidad = Integer.parseInt(params.get("cantidad"));
                
                try (Connection conn = getConexion()) {
                    String selectSql = "SELECT stock FROM producto WHERE sku = ?";
                    int stockActual = -1;
                    try (PreparedStatement selectStmt = conn.prepareStatement(selectSql)) {
                        selectStmt.setString(1, sku); ResultSet rs = selectStmt.executeQuery();
                        if (rs.next()) stockActual = rs.getInt("stock"); 
                        else { enviarRespuesta(t, 404, "{\"error\": \"Producto no encontrado.\"}"); return; }
                    }
                    if ("salida".equals(tipo) && stockActual < cantidad) { enviarRespuesta(t, 400, "{\"error\": \"Stock insuficiente.\"}"); return; }
                    
                    String updateSql = "salida".equals(tipo) ? "UPDATE producto SET stock = stock - ? WHERE sku = ?" : "UPDATE producto SET stock = stock + ? WHERE sku = ?";
                    try (PreparedStatement updateStmt = conn.prepareStatement(updateSql)) {
                        updateStmt.setInt(1, cantidad); updateStmt.setString(2, sku); updateStmt.executeUpdate();
                    }

                    String kardexSql = "INSERT INTO kardex (sku, tipo, cantidad) VALUES (?, ?, ?)";
                    try (PreparedStatement kardexStmt = conn.prepareStatement(kardexSql)) {
                        kardexStmt.setString(1, sku); kardexStmt.setString(2, tipo); kardexStmt.setInt(3, cantidad); kardexStmt.executeUpdate();
                    }
                }
                enviarRespuesta(t, 200, "{\"mensaje\": \"Movimiento registrado\"}");
            } catch (Exception e) { enviarRespuesta(t, 500, "{\"error\": \"Error procesando el movimiento.\"}"); }
        }
    }

    static class KardexHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange t) throws IOException {
            agregarCabecerasCORS(t);
            if ("OPTIONS".equals(t.getRequestMethod())) { t.sendResponseHeaders(204, -1); return; }
            StringBuilder jsonArray = new StringBuilder("[");
            String sql = "SELECT k.fecha, k.sku, p.nombre, k.tipo, k.cantidad FROM kardex k JOIN producto p ON k.sku = p.sku ORDER BY k.fecha DESC LIMIT 50";
            try (Connection conn = getConexion(); PreparedStatement pstmt = conn.prepareStatement(sql); ResultSet rs = pstmt.executeQuery()) {
                boolean primero = true;
                while (rs.next()) {
                    if (!primero) jsonArray.append(",");
                    jsonArray.append(String.format("{\"fecha\":\"%s\", \"sku\":\"%s\", \"nombre\":\"%s\", \"tipo\":\"%s\", \"cantidad\":%d}", 
                        rs.getTimestamp("fecha").toString().substring(0, 19), rs.getString("sku"), rs.getString("nombre").replace("\"", "\\\""), rs.getString("tipo"), rs.getInt("cantidad")));
                    primero = false;
                }
            } catch (Exception e) { e.printStackTrace(); }
            jsonArray.append("]");
            enviarRespuesta(t, 200, jsonArray.toString());
        }
    }

    // --- SEGURIDAD Y HERRAMIENTAS ---
    private static void agregarCabecerasCORS(HttpExchange t) {
        t.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        t.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
        t.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type, Authorization, X-Requested-With");
        t.getResponseHeaders().add("Content-Type", "application/json");
    }

    private static void enviarRespuesta(HttpExchange t, int codigo, String texto) throws IOException {
        byte[] bytes = texto.getBytes(StandardCharsets.UTF_8); t.sendResponseHeaders(codigo, bytes.length);
        OutputStream os = t.getResponseBody(); os.write(bytes); os.close();
    }

    private static Map<String, String> obtenerParametros(String query) throws Exception {
        Map<String, String> result = new HashMap<>(); if (query == null) return result;
        for (String param : query.split("&")) {
            String[] entry = param.split("="); if (entry.length > 1) result.put(entry[0], URLDecoder.decode(entry[1], StandardCharsets.UTF_8.name()));
        }
        return result;
    }
}