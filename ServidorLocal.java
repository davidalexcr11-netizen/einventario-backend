import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

// 🚨 LIBRERÍAS NUEVAS PARA LEER EL HTML
import java.io.File;
import java.nio.file.Files;

public class ServidorLocal {
    
    // --- Configuración de Base de Datos ---
    private static final String DB_URL = "jdbc:postgresql://localhost:5432/einventario_local";
    private static final String DB_USER = "postgres";
    private static final String DB_PASS = "brandon1600";

    private static Connection getConexion() throws Exception {
        Class.forName("org.postgresql.Driver");
        return DriverManager.getConnection(DB_URL, DB_USER, DB_PASS);
    }

    public static void main(String[] args) throws Exception {
        System.out.println("🚀 Iniciando Servidor Full-Stack en puerto 8080...");

        try (Connection conn = getConexion(); Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE IF NOT EXISTS producto (sku VARCHAR(50) PRIMARY KEY, nombre VARCHAR(255) NOT NULL, stock INT NOT NULL);");
            stmt.execute("CREATE TABLE IF NOT EXISTS usuario (id SERIAL PRIMARY KEY, email VARCHAR(150) UNIQUE NOT NULL, password VARCHAR(100) NOT NULL, nombre VARCHAR(100) NOT NULL);");
            stmt.execute("INSERT INTO usuario (email, password, nombre) VALUES ('admin@einventario.com', 'admin123', 'David Cadena') ON CONFLICT (email) DO NOTHING;");
            stmt.execute("CREATE TABLE IF NOT EXISTS kardex (id SERIAL PRIMARY KEY, sku VARCHAR(50) REFERENCES producto(sku) ON DELETE CASCADE, tipo VARCHAR(20) NOT NULL, cantidad INT NOT NULL, fecha TIMESTAMP DEFAULT CURRENT_TIMESTAMP);");
            stmt.execute("CREATE TABLE IF NOT EXISTS cliente (id SERIAL PRIMARY KEY, nombre VARCHAR(255) NOT NULL, email VARCHAR(255), telefono VARCHAR(50));");
            System.out.println("✅ Base de datos sincronizada.");
        } catch (Exception e) { 
            System.err.println("❌ Error conectando a PostgreSQL.");
            e.printStackTrace();
            return; 
        }

        HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);
        
        // 🌐 RUTA PARA EL FRONTEND (Esta es la que el profe quiere)
        server.createContext("/", new WebHandler());

        // --- RUTAS DE LA API (Datos) ---
        server.createContext("/api/login", new LoginHandler());
        server.createContext("/api/productos", new ListarHandler());
        server.createContext("/api/crear", new CrearHandler());
        server.createContext("/api/movimiento", new MovimientoHandler());
        server.createContext("/api/kardex", new KardexHandler());
        server.createContext("/api/eliminar", new EliminarHandler());
        server.createContext("/api/clientes", new ListarClientesHandler());
        server.createContext("/api/crearCliente", new CrearClienteHandler());
        server.createContext("/api/eliminarCliente", new EliminarClienteHandler());
        
        server.setExecutor(null);
        server.start();
        System.out.println("⭐ Servidor corriendo en http://localhost:8080");
        System.out.println("👉 Abre tu navegador y entra a: http://localhost:8080/");
    }

    // ==========================================
    //    NUEVO MANEJADOR PARA SERVIR EL HTML
    // ==========================================
    static class WebHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange t) throws IOException {
            // Solo servimos el HTML si piden la raíz "/"
            if (!t.getRequestURI().getPath().equals("/")) {
                t.sendResponseHeaders(404, -1);
                return;
            }
            
            try {
                // Busca el archivo index.html en la misma carpeta
                File file = new File("index.html");
                if (file.exists()) {
                    t.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
                    t.sendResponseHeaders(200, file.length());
                    OutputStream os = t.getResponseBody();
                    Files.copy(file.toPath(), os);
                    os.close();
                } else {
                    String err = "<h1>Error 404</h1><p>No se encontro el archivo index.html en la carpeta del servidor.</p>";
                    t.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
                    t.sendResponseHeaders(404, err.length());
                    OutputStream os = t.getResponseBody();
                    os.write(err.getBytes());
                    os.close();
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    // ==========================================
    //       RESTO DE MANEJADORES (Tus APIs)
    // ==========================================

    static class LoginHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange t) throws IOException {
            agregarCabecerasCORS(t);
            if ("OPTIONS".equals(t.getRequestMethod())) { t.sendResponseHeaders(204, -1); return; }
            try {
                Map<String, String> p = obtenerParametros(t.getRequestURI().getQuery());
                try (Connection c = getConexion(); PreparedStatement st = c.prepareStatement("SELECT nombre FROM usuario WHERE email=? AND password=?")) {
                    st.setString(1, p.get("email")); st.setString(2, p.get("password"));
                    ResultSet rs = st.executeQuery();
                    if (rs.next()) enviarRespuesta(t, 200, "{\"mensaje\":\"ok\",\"nombre\":\""+rs.getString("nombre")+"\"}");
                    else enviarRespuesta(t, 401, "{\"error\":\"fail\"}");
                }
            } catch (Exception e) { enviarRespuesta(t, 500, "{\"error\":\"err\"}"); }
        }
    }

    static class ListarHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange t) throws IOException {
            agregarCabecerasCORS(t);
            if ("OPTIONS".equals(t.getRequestMethod())) { t.sendResponseHeaders(204, -1); return; }
            StringBuilder json = new StringBuilder("[");
            try (Connection c = getConexion(); Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT * FROM producto ORDER BY nombre")) {
                boolean f = true;
                while (rs.next()) {
                    if (!f) json.append(",");
                    json.append(String.format("{\"sku\":\"%s\",\"nombre\":\"%s\",\"stock\":%d}", rs.getString("sku"), rs.getString("nombre").replace("\"", "\\\""), rs.getInt("stock")));
                    f = false;
                }
            } catch (Exception e) { }
            json.append("]");
            enviarRespuesta(t, 200, json.toString());
        }
    }

    static class CrearHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange t) throws IOException {
            agregarCabecerasCORS(t);
            if ("OPTIONS".equals(t.getRequestMethod())) { t.sendResponseHeaders(204, -1); return; }
            try {
                Map<String, String> p = obtenerParametros(t.getRequestURI().getQuery());
                try (Connection c = getConexion(); PreparedStatement st = c.prepareStatement("INSERT INTO producto VALUES (?,?,?)")) {
                    st.setString(1, p.get("sku")); st.setString(2, p.get("nombre")); st.setInt(3, Integer.parseInt(p.get("stock")));
                    st.executeUpdate();
                }
                enviarRespuesta(t, 200, "{\"mensaje\":\"ok\"}");
            } catch (Exception e) { enviarRespuesta(t, 400, "{\"error\":\"err\"}"); }
        }
    }

    static class MovimientoHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange t) throws IOException {
            agregarCabecerasCORS(t);
            if ("OPTIONS".equals(t.getRequestMethod())) { t.sendResponseHeaders(204, -1); return; }
            try {
                Map<String, String> p = obtenerParametros(t.getRequestURI().getQuery());
                String sql = p.get("tipo").equals("salida") ? "UPDATE producto SET stock=stock-? WHERE sku=?" : "UPDATE producto SET stock=stock+? WHERE sku=?";
                try (Connection c = getConexion(); PreparedStatement st = c.prepareStatement(sql)) {
                    st.setInt(1, Integer.parseInt(p.get("cantidad"))); st.setString(2, p.get("sku")); st.executeUpdate();
                    PreparedStatement sk = c.prepareStatement("INSERT INTO kardex (sku, tipo, cantidad) VALUES (?,?,?)");
                    sk.setString(1, p.get("sku")); sk.setString(2, p.get("tipo")); sk.setInt(3, Integer.parseInt(p.get("cantidad")));
                    sk.executeUpdate();
                }
                enviarRespuesta(t, 200, "{\"mensaje\":\"ok\"}");
            } catch (Exception e) { enviarRespuesta(t, 500, "{\"error\":\"err\"}"); }
        }
    }

    static class EliminarHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange t) throws IOException {
            agregarCabecerasCORS(t);
            if ("OPTIONS".equals(t.getRequestMethod())) { t.sendResponseHeaders(204, -1); return; }
            try {
                Map<String, String> p = obtenerParametros(t.getRequestURI().getQuery());
                try (Connection c = getConexion(); PreparedStatement st = c.prepareStatement("DELETE FROM producto WHERE sku=?")) {
                    st.setString(1, p.get("sku")); st.executeUpdate();
                }
                enviarRespuesta(t, 200, "{\"mensaje\":\"ok\"}");
            } catch (Exception e) { enviarRespuesta(t, 500, "{\"error\":\"err\"}"); }
        }
    }

    static class KardexHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange t) throws IOException {
            agregarCabecerasCORS(t);
            StringBuilder j = new StringBuilder("[");
            try (Connection c = getConexion(); Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT k.*, p.nombre FROM kardex k JOIN producto p ON k.sku=p.sku ORDER BY k.fecha DESC")) {
                boolean f = true;
                while (rs.next()) {
                    if (!f) j.append(",");
                    j.append(String.format("{\"fecha\":\"%s\",\"sku\":\"%s\",\"nombre\":\"%s\",\"tipo\":\"%s\",\"cantidad\":%d}", 
                        rs.getString("fecha"), rs.getString("sku"), rs.getString("nombre").replace("\"", "\\\""), rs.getString("tipo"), rs.getInt("cantidad")));
                    f = false;
                }
            } catch (Exception e) { }
            j.append("]");
            enviarRespuesta(t, 200, j.toString());
        }
    }

    static class ListarClientesHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange t) throws IOException {
            agregarCabecerasCORS(t);
            if ("OPTIONS".equals(t.getRequestMethod())) { t.sendResponseHeaders(204, -1); return; }
            StringBuilder json = new StringBuilder("[");
            try (Connection c = getConexion(); Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT * FROM cliente ORDER BY nombre")) {
                boolean f = true;
                while (rs.next()) {
                    if (!f) json.append(",");
                    json.append(String.format("{\"id\":%d, \"nombre\":\"%s\", \"email\":\"%s\", \"telefono\":\"%s\"}", 
                        rs.getInt("id"), rs.getString("nombre"), rs.getString("email"), rs.getString("telefono")));
                    f = false;
                }
            } catch (Exception e) { }
            json.append("]");
            enviarRespuesta(t, 200, json.toString());
        }
    }

    static class CrearClienteHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange t) throws IOException {
            agregarCabecerasCORS(t);
            if ("OPTIONS".equals(t.getRequestMethod())) { t.sendResponseHeaders(204, -1); return; }
            try {
                Map<String, String> p = obtenerParametros(t.getRequestURI().getQuery());
                try (Connection c = getConexion(); PreparedStatement st = c.prepareStatement("INSERT INTO cliente (nombre, email, telefono) VALUES (?,?,?)")) {
                    st.setString(1, p.get("nombre")); st.setString(2, p.get("email")); st.setString(3, p.get("telefono"));
                    st.executeUpdate();
                }
                enviarRespuesta(t, 200, "{\"mensaje\":\"ok\"}");
            } catch (Exception e) { enviarRespuesta(t, 400, "{\"error\":\"err\"}"); }
        }
    }

    static class EliminarClienteHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange t) throws IOException {
            agregarCabecerasCORS(t);
            if ("OPTIONS".equals(t.getRequestMethod())) { t.sendResponseHeaders(204, -1); return; }
            try {
                Map<String, String> p = obtenerParametros(t.getRequestURI().getQuery());
                try (Connection c = getConexion(); PreparedStatement st = c.prepareStatement("DELETE FROM cliente WHERE id=?")) {
                    st.setInt(1, Integer.parseInt(p.get("id"))); st.executeUpdate();
                }
                enviarRespuesta(t, 200, "{\"mensaje\":\"ok\"}");
            } catch (Exception e) { enviarRespuesta(t, 500, "{\"error\":\"err\"}"); }
        }
    }

    private static void agregarCabecerasCORS(HttpExchange t) {
        t.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        t.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, POST, DELETE, OPTIONS");
        t.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type");
        t.getResponseHeaders().add("Content-Type", "application/json");
    }

    private static void enviarRespuesta(HttpExchange t, int c, String txt) throws IOException {
        byte[] b = txt.getBytes(StandardCharsets.UTF_8); t.sendResponseHeaders(c, b.length);
        OutputStream o = t.getResponseBody(); o.write(b); o.close();
    }

    private static Map<String, String> obtenerParametros(String q) throws Exception {
        Map<String, String> r = new HashMap<>(); if (q == null) return r;
        for (String s : q.split("&")) { String[] e = s.split("="); if (e.length > 1) r.put(e[0], URLDecoder.decode(e[1], "UTF-8")); }
        return r;
    }
}