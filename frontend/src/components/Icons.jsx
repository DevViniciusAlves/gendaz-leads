// Inline SVG icons (no emojis, no external icon libraries).

const base = {
  width: 18,
  height: 18,
  viewBox: '0 0 24 24',
  fill: 'none',
  stroke: 'currentColor',
  strokeWidth: 1.8,
  strokeLinecap: 'round',
  strokeLinejoin: 'round'
}

export function IconDashboard(p) {
  return (
    <svg {...base} {...p}>
      <rect x="3" y="3" width="7" height="9" rx="1" />
      <rect x="14" y="3" width="7" height="5" rx="1" />
      <rect x="14" y="12" width="7" height="9" rx="1" />
      <rect x="3" y="16" width="7" height="5" rx="1" />
    </svg>
  )
}

export function IconCampaigns(p) {
  return (
    <svg {...base} {...p}>
      <path d="M3 7l9-4 9 4-9 4-9-4z" />
      <path d="M3 12l9 4 9-4" />
      <path d="M3 17l9 4 9-4" />
    </svg>
  )
}

export function IconLeads(p) {
  return (
    <svg {...base} {...p}>
      <circle cx="12" cy="8" r="4" />
      <path d="M4 21c0-4 4-6 8-6s8 2 8 6" />
    </svg>
  )
}

export function IconLogout(p) {
  return (
    <svg {...base} {...p}>
      <path d="M15 12H3" />
      <path d="M7 8l-4 4 4 4" />
      <path d="M15 4h4a2 2 0 012 2v12a2 2 0 01-2 2h-4" />
    </svg>
  )
}

export function IconClose(p) {
  return (
    <svg {...base} {...p}>
      <path d="M18 6L6 18" />
      <path d="M6 6l12 12" />
    </svg>
  )
}

export function IconCopy(p) {
  return (
    <svg {...base} {...p}>
      <rect x="9" y="9" width="11" height="11" rx="2" />
      <path d="M5 15V5a2 2 0 012-2h10" />
    </svg>
  )
}

export function IconExternal(p) {
  return (
    <svg {...base} {...p}>
      <path d="M14 4h6v6" />
      <path d="M20 4l-9 9" />
      <path d="M19 14v5a2 2 0 01-2 2H5a2 2 0 01-2-2V7a2 2 0 012-2h5" />
    </svg>
  )
}

export function IconRefresh(p) {
  return (
    <svg {...base} {...p}>
      <path d="M21 12a9 9 0 11-3-6.7" />
      <path d="M21 4v4h-4" />
    </svg>
  )
}

export function IconPlus(p) {
  return (
    <svg {...base} {...p}>
      <path d="M12 5v14" />
      <path d="M5 12h14" />
    </svg>
  )
}
