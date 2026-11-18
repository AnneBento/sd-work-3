package sd.api;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public class ServidorApi {

    // Modelos de domínio (reaproveitados do contexto do Trabalho 2/4)
    static class Usuario {
        String login;
        String senha;
        String nome;
        String papel; // ADMINISTRADOR ou ASSINANTE

        Usuario(String login, String senha, String nome, String papel) {
            this.login = login;
            this.senha = senha;
            this.nome = nome;
            this.papel = papel;
        }
    }

    static class Topico {
        String nome;
        String descricao;
        Set<String> assinantes = ConcurrentHashMap.newKeySet();

        Topico(String nome, String descricao) {
            this.nome = nome;
            this.descricao = descricao;
        }
    }

    static class Mensagem {
        int id;
        String topico;
        String tipo; // NOTIFICACAO, ALERTA, ATUALIZACAO
        String autor;
        String conteudo;
        String timestamp;

        Mensagem(int id, String topico, String tipo, String autor, String conteudo) {
            this.id = id;
            this.topico = topico;
            this.tipo = tipo;
            this.autor = autor;
            this.conteudo = conteudo;
            this.timestamp = Instant.now().toString();
        }
    }

    // Repositórios em memória (thread-safe)
    private static final Map<String, Usuario> usuarios = new ConcurrentHashMap<>();
    private static final Map<String, String> tokens = new ConcurrentHashMap<>();
    private static final Map<String, Topico> topicos = new ConcurrentHashMap<>();
    private static final List<Mensagem> mensagens = new CopyOnWriteArrayList<>();
    private static final AtomicInteger seqMensagem = new AtomicInteger(1);

    public static void main(String[] args) throws IOException {
        int porta = args.length > 0 ? Integer.parseInt(args[0]) : 8080;
        inicializarDados();

        HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", porta), 0);

        // 3 Objetos Distribuídos / Recursos REST distintos
        server.createContext("/api/usuarios", new UsuariosHandler());
        server.createContext("/api/topicos", new TopicosHandler());
        server.createContext("/api/mensagens", new MensagensHandler());

        server.setExecutor(Executors.newFixedThreadPool(10));
        server.start();

        System.out.println("=========================================================");
        System.out.println(" Servidor API REST (Trabalho 3 - SD) rodando na porta " + porta);
        System.out.println(" Recursos expostos:");
        System.out.println("   1. /api/usuarios  (GET, POST)");
        System.out.println("   2. /api/topicos   (GET, POST)");
        System.out.println("   3. /api/mensagens (GET, POST)");
        System.out.println("=========================================================");
    }

    private static void inicializarDados() {
        usuarios.put("admin", new Usuario("admin", "admin123", "Administrador Geral", "ADMINISTRADOR"));
        usuarios.put("ana", new Usuario("ana", "123", "Ana Silva", "ASSINANTE"));
        usuarios.put("bruno", new Usuario("bruno", "123", "Bruno Costa", "ASSINANTE"));
        usuarios.put("carla", new Usuario("carla", "123", "Carla Souza", "ASSINANTE"));

        Topico geral = new Topico("geral", "Avisos e comunicados gerais do sistema");
        geral.assinantes.add("ana");
        geral.assinantes.add("bruno");
        topicos.put("geral", geral);

        Topico alertas = new Topico("alertas", "Alertas criticos e urgentes");
        alertas.assinantes.add("ana");
        topicos.put("alertas", alertas);

        mensagens.add(new Mensagem(seqMensagem.getAndIncrement(), "geral", "NOTIFICACAO", "admin", "Bem-vindos ao servico REST de mensagens!"));
    }

    // --- RECURSO 1: /api/usuarios ---
    static class UsuariosHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            if (tratarCors(ex)) return;
            String method = ex.getRequestMethod();
            String path = ex.getRequestURI().getPath();

            try {
                if ("GET".equalsIgnoreCase(method) && "/api/usuarios".equals(path)) {
                    StringBuilder sb = new StringBuilder("[");
                    boolean first = true;
                    for (Usuario u : usuarios.values()) {
                        if (!first) sb.append(",");
                        first = false;
                        sb.append(String.format("{"login":"%s","nome":"%s","papel":"%s"}",
                                esc(u.login), esc(u.nome), esc(u.papel)));
                    }
                    sb.append("]");
                    responder(ex, 200, sb.toString());
                } else if ("POST".equalsIgnoreCase(method) && "/api/usuarios/login".equals(path)) {
                    Map<String, String> body = parseJsonSimples(lerCorpo(ex));
                    String login = body.get("login");
                    String senha = body.get("senha");
                    Usuario u = login != null ? usuarios.get(login) : null;
                    if (u == null || !u.senha.equals(senha)) {
                        responder(ex, 401, "{"erro":"Credenciais invalidas"}");
                        return;
                    }
                    String token = UUID.randomUUID().toString();
                    tokens.put(token, u.login);
                    String resp = String.format("{"token":"%s","login":"%s","nome":"%s","papel":"%s"}",
                            token, esc(u.login), esc(u.nome), esc(u.papel));
                    responder(ex, 200, resp);
                } else if ("POST".equalsIgnoreCase(method) && "/api/usuarios".equals(path)) {
                    Map<String, String> body = parseJsonSimples(lerCorpo(ex));
                    String login = body.get("login");
                    String senha = body.get("senha");
                    String nome = body.getOrDefault("nome", login);
                    String papel = body.getOrDefault("papel", "ASSINANTE").toUpperCase();
                    if (login == null || login.isBlank() || senha == null || senha.isBlank()) {
                        responder(ex, 400, "{"erro":"Login e senha sao obrigatorios"}");
                        return;
                    }
                    if (usuarios.containsKey(login)) {
                        responder(ex, 409, "{"erro":"Usuario ja existe"}");
                        return;
                    }
                    Usuario novo = new Usuario(login, senha, nome, papel);
                    usuarios.put(login, novo);
                    responder(ex, 201, String.format("{"mensagem":"Usuario cadastrado","login":"%s","papel":"%s"}",
                            esc(novo.login), esc(novo.papel)));
                } else {
                    responder(ex, 404, "{"erro":"Rota nao encontrada em /api/usuarios"}");
                }
            } catch (Exception e) {
                responder(ex, 500, "{"erro":"" + esc(e.getMessage()) + ""}");
            }
        }
    }

    // --- RECURSO 2: /api/topicos ---
    static class TopicosHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            if (tratarCors(ex)) return;
            String method = ex.getRequestMethod();
            String path = ex.getRequestURI().getPath();

            try {
                if ("GET".equalsIgnoreCase(method) && "/api/topicos".equals(path)) {
                    StringBuilder sb = new StringBuilder("[");
                    boolean first = true;
                    for (Topico t : topicos.values()) {
                        if (!first) sb.append(",");
                        first = false;
                        sb.append(String.format("{"nome":"%s","descricao":"%s","totalAssinantes":%d,"assinantes":%s}",
                                esc(t.nome), esc(t.descricao), t.assinantes.size(), toJsonArray(t.assinantes)));
                    }
                    sb.append("]");
                    responder(ex, 200, sb.toString());
                } else if ("POST".equalsIgnoreCase(method) && "/api/topicos".equals(path)) {
                    Usuario u = autenticar(ex);
                    if (u == null) {
                        responder(ex, 401, "{"erro":"Token ausente ou invalido"}");
                        return;
                    }
                    if (!"ADMINISTRADOR".equals(u.papel)) {
                        responder(ex, 403, "{"erro":"Apenas ADMINISTRADOR pode criar topicos"}");
                        return;
                    }
                    Map<String, String> body = parseJsonSimples(lerCorpo(ex));
                    String nome = body.get("nome");
                    String desc = body.getOrDefault("descricao", "Topico criado via API");
                    if (nome == null || nome.isBlank()) {
                        responder(ex, 400, "{"erro":"Nome do topico obrigatorio"}");
                        return;
                    }
                    topicos.putIfAbsent(nome.toLowerCase(), new Topico(nome.toLowerCase(), desc));
                    responder(ex, 201, String.format("{"mensagem":"Topico disponivel","nome":"%s"}", esc(nome.toLowerCase())));
                } else if ("POST".equalsIgnoreCase(method) && "/api/topicos/assinar".equals(path)) {
                    Usuario u = autenticar(ex);
                    if (u == null) {
                        responder(ex, 401, "{"erro":"Token ausente ou invalido"}");
                        return;
                    }
                    Map<String, String> body = parseJsonSimples(lerCorpo(ex));
                    String nomeTopico = body.get("topico");
                    Topico t = nomeTopico != null ? topicos.get(nomeTopico.toLowerCase()) : null;
                    if (t == null) {
                        responder(ex, 404, "{"erro":"Topico nao encontrado"}");
                        return;
                    }
                    t.assinantes.add(u.login);
                    responder(ex, 200, String.format("{"mensagem":"Assinatura confirmada","usuario":"%s","topico":"%s"}",
                            esc(u.login), esc(t.nome)));
                } else {
                    responder(ex, 404, "{"erro":"Rota nao encontrada em /api/topicos"}");
                }
            } catch (Exception e) {
                responder(ex, 500, "{"erro":"" + esc(e.getMessage()) + ""}");
            }
        }
    }

    // --- RECURSO 3: /api/mensagens ---
    static class MensagensHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            if (tratarCors(ex)) return;
            String method = ex.getRequestMethod();
            String path = ex.getRequestURI().getPath();

            try {
                if ("GET".equalsIgnoreCase(method) && "/api/mensagens".equals(path)) {
                    Map<String, String> query = parseQuery(ex.getRequestURI().getQuery());
                    String filtroTopico = query.get("topico");
                    StringBuilder sb = new StringBuilder("[");
                    boolean first = true;
                    for (Mensagem m : mensagens) {
                        if (filtroTopico != null && !filtroTopico.isBlank() && !m.topico.equalsIgnoreCase(filtroTopico)) {
                            continue;
                        }
                        if (!first) sb.append(",");
                        first = false;
                        sb.append(String.format("{"id":%d,"topico":"%s","tipo":"%s","autor":"%s","conteudo":"%s","timestamp":"%s"}",
                                m.id, esc(m.topico), esc(m.tipo), esc(m.autor), esc(m.conteudo), esc(m.timestamp)));
                    }
                    sb.append("]");
                    responder(ex, 200, sb.toString());
                } else if ("POST".equalsIgnoreCase(method) && "/api/mensagens".equals(path)) {
                    Usuario u = autenticar(ex);
                    if (u == null) {
                        responder(ex, 401, "{"erro":"Token ausente ou invalido"}");
                        return;
                    }
                    if (!"ADMINISTRADOR".equals(u.papel)) {
                        responder(ex, 403, "{"erro":"Apenas ADMINISTRADOR pode publicar mensagens"}");
                        return;
                    }
                    Map<String, String> body = parseJsonSimples(lerCorpo(ex));
                    String topicoNome = body.getOrDefault("topico", "geral").toLowerCase();
                    String tipo = body.getOrDefault("tipo", "NOTIFICACAO").toUpperCase();
                    String conteudo = body.get("conteudo");

                    if (!topicos.containsKey(topicoNome)) {
                        responder(ex, 404, "{"erro":"Topico inexistente"}");
                        return;
                    }
                    if (conteudo == null || conteudo.isBlank()) {
                        responder(ex, 400, "{"erro":"Conteudo da mensagem e obrigatorio"}");
                        return;
                    }
                    Mensagem nova = new Mensagem(seqMensagem.getAndIncrement(), topicoNome, tipo, u.login, conteudo);
                    mensagens.add(nova);
                    responder(ex, 201, String.format("{"id":%d,"topico":"%s","tipo":"%s","autor":"%s","conteudo":"%s","timestamp":"%s"}",
                            nova.id, esc(nova.topico), esc(nova.tipo), esc(nova.autor), esc(nova.conteudo), esc(nova.timestamp)));
                } else {
                    responder(ex, 404, "{"erro":"Rota nao encontrada em /api/mensagens"}");
                }
            } catch (Exception e) {
                responder(ex, 500, "{"erro":"" + esc(e.getMessage()) + ""}");
            }
        }
    }

    // --- Utilitários HTTP / JSON ---
    private static Usuario autenticar(HttpExchange ex) {
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        if (auth == null || !auth.startsWith("Bearer ")) return null;
        String token = auth.substring(7).trim();
        String login = tokens.get(token);
        return login != null ? usuarios.get(login) : null;
    }

    private static boolean tratarCors(HttpExchange ex) throws IOException {
        ex.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        ex.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        ex.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type, Authorization");
        if ("OPTIONS".equalsIgnoreCase(ex.getRequestMethod())) {
            ex.sendResponseHeaders(204, -1);
            return true;
        }
        return false;
    }

    private static void responder(HttpExchange ex, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static String lerCorpo(HttpExchange ex) throws IOException {
        try (InputStream is = ex.getRequestBody()) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static Map<String, String> parseJsonSimples(String json) {
        Map<String, String> map = new HashMap<>();
        if (json == null) return map;
        String limpo = json.trim();
        if (limpo.startsWith("{")) limpo = limpo.substring(1);
        if (limpo.endsWith("}")) limpo = limpo.substring(0, limpo.length() - 1);
        for (String par : limpo.split(",(?=(?:[^"]*"[^"]*")*[^"]*$)")) {
            String[] kv = par.split(":", 2);
            if (kv.length == 2) {
                String k = removerAspas(kv[0].trim());
                String v = removerAspas(kv[1].trim());
                map.put(k, v);
            }
        }
        return map;
    }

    private static Map<String, String> parseQuery(String query) {
        Map<String, String> map = new HashMap<>();
        if (query == null || query.isBlank()) return map;
        for (String param : query.split("&")) {
            String[] kv = param.split("=", 2);
            if (kv.length == 2) map.put(kv[0], kv[1]);
        }
        return map;
    }

    private static String removerAspas(String s) {
        if (s.startsWith(""") && s.endsWith(""") && s.length() >= 2) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    private static String toJsonArray(Collection<String> itens) {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (String item : itens) {
            if (!first) sb.append(",");
            first = false;
            sb.append(""").append(esc(item)).append(""");
        }
        sb.append("]");
        return sb.toString();
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace(""", "\\"").replace("\n", "\\n");
    }
}
