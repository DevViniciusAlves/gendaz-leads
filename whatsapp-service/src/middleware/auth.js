'use strict';

// Autenticacao dos endpoints internos via token compartilhado (fail closed).
function internalAuth(config) {
  return (req, res, next) => {
    if (!config.internalToken) {
      return res.status(500).json({ error: 'internal_auth_not_configured' });
    }
    const header = req.headers['authorization'] || '';
    const [scheme, token] = String(header).split(' ');
    if (scheme !== 'Bearer' || !token || token !== config.internalToken) {
      return res.status(401).json({ error: 'unauthorized' });
    }
    return next();
  };
}

module.exports = { internalAuth };
