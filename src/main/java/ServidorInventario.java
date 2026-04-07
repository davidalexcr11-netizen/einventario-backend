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
    
    // CONFIGURACIÓN DE LA BASE DE DATOS
// Ruta alternativa usando el dominio directo de Supabase
// 1. URL limpia, con el puerto 6543 y el certificado de seguridad obligatorio
private static final String DB_URL = "jdbc:postgresql://aws-1-us-east-1.pooler.supabase.com:6543/postgres?sslmode=require";

// 2. Usuario completo (con tu ID de Supabase)
private static final String DB_USER = "postgres.yrmxmdjmsvsnqsnekjkf"; 

// 3. Tu contraseña real (Pega la que me mandaste aquí)
private static final String DB_PASSWORD = "DSozr97Fl1fPFxPY";

    private static Connection getConexion() throws Exception {
        return DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD);
    }

    public static void main(String[] args) throws Exception {
        try (Connection conn = getConexion(); Statement stmt = conn.createStatement()) {
            // Crear tabla de productos
            stmt.execute("CREATE TABLE IF NOT EXISTS producto (sku VARCHAR(50) PRIMARY KEY, nombre VARCHAR(255) NOT NULL, stock INT NOT NULL);");
            
            // Crear tabla de usuarios
            stmt.execute("CREATE TABLE IF NOT EXISTS usuario (id SERIAL PRIMARY KEY, email VARCHAR(150) UNIQUE NOT NULL, password VARCHAR(100) NOT NULL, nombre VARCHAR(100) NOT NULL);");
            
            // Insertar el usuario administrador por defecto (Ignora si ya existe)
            stmt.execute("INSERT INTO usuario (email, password, nombre) VALUES ('admin@einventario.com', 'admin123', 'Administrador Principal') ON CONFLICT (email) DO NOTHING;");
            
            System.out.println("✅ Base de datos verificada. Módulo de seguridad activo.");
        } catch (Exception e) {
            System.err.println("❌ Error conectando a PostgreSQL.");
            e.printStackTrace();
            return; 
        }

        HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);
        server.createContext("/api/login", new LoginHandler());
        server.createContext("/api/productos", new ListarHandler());
        server.createContext("/api/crear", new CrearHandler());
        server.createContext("/api/movimiento", new MovimientoHandler());
        server.setExecutor(null);
        server.start();
        System.out.println("🚀 Backend protegido corriendo en http://localhost:8080");
    }

    // Ruta de Seguridad: Validar credenciales
    static class LoginHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange t) throws IOException {
            agregarCabecerasCORS(t);
            if ("OPTIONS".equals(t.getRequestMethod())) { t.sendResponseHeaders(204, -1); return; }

            try {
                Map<String, String> params = obtenerParametros(t.getRequestURI().getQuery());
                String email = params.get("email");
                String password = params.get("password");

                String sql = "SELECT nombre FROM usuario WHERE email = ? AND password = ?";
                try (Connection conn = getConexion(); PreparedStatement pstmt = conn.prepareStatement(sql)) {
                    pstmt.setString(1, email);
                    pstmt.setString(2, password);
                    ResultSet rs = pstmt.executeQuery();
                    
                    if (rs.next()) {
                        String nombreUsuario = rs.getString("nombre");
                        System.out.println("🔐 Inicio de sesión exitoso: " + nombreUsuario);
                        enviarRespuesta(t, 200, "{\"mensaje\": \"Acceso concedido\", \"nombre\": \"" + nombreUsuario + "\"}");
                    } else {
                        System.out.println("🚫 Intento de acceso fallido para: " + email);
                        enviarRespuesta(t, 401, "{\"error\": \"Credenciales incorrectas\"}");
                    }
                }
            } catch (Exception e) {
                enviarRespuesta(t, 500, "{\"error\": \"Error procesando el login.\"}");
            }
        }
    }

    // Las rutas anteriores se mantienen igual
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
                        if (rs.next()) stockActual = rs.getInt("stock"); else { enviarRespuesta(t, 404, "{\"error\": \"Producto no encontrado.\"}"); return; }
                    }
                    if ("salida".equals(tipo) && stockActual < cantidad) { enviarRespuesta(t, 400, "{\"error\": \"Stock insuficiente.\"}"); return; }
                    String updateSql = "salida".equals(tipo) ? "UPDATE producto SET stock = stock - ? WHERE sku = ?" : "UPDATE producto SET stock = stock + ? WHERE sku = ?";
                    try (PreparedStatement updateStmt = conn.prepareStatement(updateSql)) {
                        updateStmt.setInt(1, cantidad); updateStmt.setString(2, sku); updateStmt.executeUpdate();
                    }
                }
                enviarRespuesta(t, 200, "{\"mensaje\": \"Movimiento exitoso\"}");
            } catch (Exception e) { enviarRespuesta(t, 500, "{\"error\": \"Error procesando el movimiento.\"}"); }
        }
    }

    private static void agregarCabecerasCORS(HttpExchange t) {
        t.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        t.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
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