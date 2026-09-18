'use strict';

// Mascaramento seguro para logs: nunca logar telefone/JID integral.
function maskPhone(phone) {
  const digits = String(phone || '').replace(/\D/g, '');
  if (digits.length <= 4) return '***';
  return `***${digits.slice(-4)}`;
}

module.exports = { maskPhone };
