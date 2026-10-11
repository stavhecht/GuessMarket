import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import type { IncomingMessage, ServerResponse } from 'node:http';

/*
 * The web client's own little server: it hands the browser the React app and forwards
 * every /api request to the Tomcat server, unchanged, so that the existing server does not
 * have to know this client exists.
 *
 * Why a forwarder at all: the page is served from http://localhost:3000 and the market
 * lives at http://localhost:8080/GuessMarket. A browser will not let a page on one origin
 * read the answers of another unless that server sends CORS headers, and the Tomcat server
 * sends none. Adding them would mean changing the server, which this client must not do.
 * Forwarded through here, every request the browser makes is to its own origin, and Tomcat
 * sees an ordinary HTTP client, exactly as it sees the JavaFX client's OkHttp.
 *
 * Why the session travels in a header and not as a cookie: Tomcat says who you are with a
 * JSESSIONID cookie. If the browser kept it, every tab of the same browser would share one
 * cookie and therefore one login, so a second tab logging in as somebody else would take
 * the first tab's session over. The JavaFX client keeps its cookie in memory, one jar per
 * window, and this does the same: the forwarder lifts the cookie out of Tomcat's answer and
 * hands it to the page as the X-GM-Session header, the page keeps it in memory, and sends it
 * back with each request, where it is turned into a Cookie again. One tab is one client, and
 * closing it (or reloading it) is closing that client.
 */

/** Where the market is: the same address the JavaFX client defaults to. */
const SERVER = (process.env.GM_SERVER || 'http://localhost:8080/GuessMarket').replace(/\/+$/, '');
const PORT = Number(process.env.GM_PORT || 3000);

const server = new URL(SERVER);
const contextPath = server.pathname.replace(/\/+$/, '');   // "/GuessMarket"
const SESSION_HEADER = 'x-gm-session';
/** The market's address, told to the page on every answer, which names it in the status line. */
const SERVER_HEADER = 'x-gm-server';

export default defineConfig({
  plugins: [react()],
  server: {
    port: PORT,
    strictPort: true,
    open: process.env.GM_OPEN === '0' ? false : '/',
    proxy: {
      '/api': {
        target: server.origin,
        changeOrigin: true,
        rewrite: (path) => contextPath + path,
        configure: (proxy) => {
          proxy.on('proxyReq', (proxyReq, req) => {
            const session = req.headers[SESSION_HEADER];
            proxyReq.removeHeader('cookie');      // nothing of the browser's own reaches Tomcat
            proxyReq.removeHeader(SESSION_HEADER);
            if (typeof session === 'string' && /^[A-Za-z0-9._-]+$/.test(session)) {
              proxyReq.setHeader('cookie', 'JSESSIONID=' + session);
            }
          });
          proxy.on('proxyRes', (proxyRes) => {
            proxyRes.headers[SERVER_HEADER] = SERVER;
            const cookies = proxyRes.headers['set-cookie'];
            if (cookies) {
              for (const cookie of cookies) {
                const match = /^JSESSIONID=([^;]+)/.exec(cookie);
                if (match) {
                  proxyRes.headers[SESSION_HEADER] = match[1];
                }
              }
              delete proxyRes.headers['set-cookie'];
            }
          });
          // Tomcat not answering: say so in the shape the page reads every refusal in, an
          // ErrorBody, instead of an empty 500 the page could only call "the server replied 500".
          proxy.on('error', (error, _req, res) => {
            const response = res as ServerResponse<IncomingMessage>;
            if (!response || typeof response.writeHead !== 'function' || response.headersSent) {
              return;
            }
            // A refused connection comes as an AggregateError whose message is empty; its code says it.
            const reason = error.message || (error as NodeJS.ErrnoException).code || 'no answer';
            response.writeHead(502, { 'Content-Type': 'application/json; charset=utf-8', [SERVER_HEADER]: SERVER });
            response.end(JSON.stringify({
              error: 'Cannot reach the server at ' + SERVER + ' (' + reason + '). Is Tomcat running with GuessMarket deployed?',
              type: 'Unreachable',
            }));
          });
        },
      },
    },
  },
});
