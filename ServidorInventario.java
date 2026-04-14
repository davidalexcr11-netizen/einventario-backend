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

public class ServidorInventario {
    
    // CONFIGURACIÓN DE LA BASE DE DATOS (NUBE - SUPABASE)
    private static final String DB_URL = "jdbc:postgresql://aws-1-us-east-1.pooler.supabase.com:6543/postgres?sslmode=require";
    private static final String DB_USER = "postgres.yrmxmdjmsvsnqsnekjkf"; 
    private static final String DB_PASSWORD = "DSozr97Fl1fPFxPY"; // 🚨 ¡No olvides poner tu contraseña real!

    private static Connection getConexion() throws Exception {
        return DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD);
    }

    public static void main(String[] args) throws Exception {
        try (Connection conn = getConexion(); Statement stmt = conn.createStatement()) {
            // Tablas originales
            stmt.execute("CREATE TABLE IF NOT EXISTS producto (sku VARCHAR(50) PRIMARY KEY, nombre VARCHAR(255) NOT NULL, stock INT NOT NULL);");
            stmt.execute("CREATE TABLE IF NOT EXISTS usuario (id SERIAL PRIMARY KEY, email VARCHAR(150) UNIQUE NOT NULL, password VARCHAR(100) NOT NULL, nombre VARCHAR(100) NOT NULL);");
            stmt.execute("INSERT INTO usuario (email, password, nombre) VALUES ('admin@einventario.com', 'admin123', 'Administrador Principal') ON CONFLICT (email) DO NOTHING;");
            
            // 🔥 NUEVA TABLA: KÁRDEX (Historial de movimientos)
            stmt.execute("CREATE TABLE IF NOT EXISTS kardex (id SERIAL PRIMARY KEY, sku VARCHAR(50) REFERENCES producto(sku), tipo VARCHAR(20) NOT NULL, cantidad INT NOT NULL, fecha TIMESTAMP DEFAULT CURRENT_TIMESTAMP);");
            
            System.out.println("✅ Base de datos verificada. Módulo Kárdex activo.");
        } catch (Exception e) {
            System.err.println("❌ Error conectando a PostgreSQL.");
            e.printStackTrace();
            return; 
        }

        String portStr = System.getenv("PORT");
        int port = (portStr != null) ? Integer.parseInt(portStr) : 8080;

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/api/login", new LoginHandler());
        server.createContext("/api/productos", new ListarHandler());
        server.createContext("/api/crear", new CrearHandler());
        server.createContext("/api/movimiento", new MovimientoHandler());
        
        // 🔥 NUEVA RUTA PARA LEER EL HISTORIAL
        server.createContext("/api/kardex", new KardexHandler());
        
        server.setExecutor(null);
        server.start();
        System.out.println("🚀 Backend protegido corriendo en el puerto: " + port);
    }

    // --- MANEJADORES EXISTENTES (LOGIN, LISTAR, CREAR) ---
    static class LoginHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange t) throws IOException {
            agregarCabecerasCORS(t);
            if ("OPTIONS".equals(t.getRequestMethod())) { t.sendResponseHeaders(204, -1); return; }
            try {
                Map<String, String> params = obtenerParametros(t.getRequestURI().getQuery());
                String sql = "SELECT nombre FROM usuario WHERE email = ? AND password = ?";
                try (Connection conn = getConexion(); PreparedStatement pstmt = conn.prepareStatement(sql)) {
                    pstmt.setString(1, params.get("email")); pstmt.setString(2, params.get("password"));
                    ResultSet rs = pstmt.executeQuery();
                    if (rs.next()) enviarRespuesta(t, 200, "{\"mensaje\": \"Acceso concedido\", \"nombre\": \"" + rs.getString("nombre") + "\"}");
                    else enviarRespuesta(t, 401, "{\"error\": \"Credenciales incorrectas\"}");
                }
            } catch (Exception e) { enviarRespuesta(t, 500, "{\"error\": \"Error procesando el login.\"}"); }
        }
    }

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
            } catch (Exception e) { enviarRespuesta(t, 400, "{\"error\": \"El SKU ya existe o los datos son inválidos.\"}"); }
        }
    }

    // 🔥 MANEJADOR MODIFICADO: AHORA GUARDA EN EL KÁRDEX
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
                    
                    // 1. Actualizar el stock del producto
                    String updateSql = "salida".equals(tipo) ? "UPDATE producto SET stock = stock - ? WHERE sku = ?" : "UPDATE producto SET stock = stock + ? WHERE sku = ?";
                    try (PreparedStatement updateStmt = conn.prepareStatement(updateSql)) {
                        updateStmt.setInt(1, cantidad); updateStmt.setString(2, sku); updateStmt.executeUpdate();
                    }

                    // 2. 🔥 REGISTRAR EL MOVIMIENTO EN EL KÁRDEX
                    String kardexSql = "INSERT INTO kardex (sku, tipo, cantidad) VALUES (?, ?, ?)";
                    try (PreparedStatement kardexStmt = conn.prepareStatement(kardexSql)) {
                        kardexStmt.setString(1, sku);
                        kardexStmt.setString(2, tipo);
                        kardexStmt.setInt(3, cantidad);
                        kardexStmt.executeUpdate();
                    }
                }
                enviarRespuesta(t, 200, "{\"mensaje\": \"Movimiento exitoso y registrado en Kárdex\"}");
            } catch (Exception e) { enviarRespuesta(t, 500, "{\"error\": \"Error procesando el movimiento.\"}"); }
        }
    }

    // 🔥 NUEVO MANEJADOR: PARA LEER EL HISTORIAL
    static class KardexHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange t) throws IOException {
            agregarCabecerasCORS(t);
            if ("OPTIONS".equals(t.getRequestMethod())) { t.sendResponseHeaders(204, -1); return; }
            StringBuilder jsonArray = new StringBuilder("[");
            // Unimos la tabla kardex con producto para traer el nombre también
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