const http = require('http');
const PORT = process.env.PORT || 4510;

const server = http.createServer((req, res) => {
  const { method, url } = req;

  // @endpoint GET /availability
  if (method === 'GET' && url.startsWith('/availability')) {
    const mockResponseName = req.headers['x-mock-response-name'] || 'available';

    if (mockResponseName === 'unavailable') {
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ externalAvailable: false }));
      return;
    }

    if (mockResponseName === 'upstream-error') {
      res.writeHead(503, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ error: 'availability service temporarily unavailable' }));
      return;
    }

    // default: available
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ externalAvailable: true }));
    return;
  }

  res.writeHead(404, { 'Content-Type': 'application/json' });
  res.end(JSON.stringify({ error: 'Mock route not defined', method, url }));
});

server.listen(PORT, () => console.log('SP05 Availability Mock running on port ' + PORT));
