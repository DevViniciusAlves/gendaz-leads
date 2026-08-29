Place the "Faktum Medium Fina" web font here as: Faktum-MediumFina.woff2

The application references it from src/styles.css via:
  @font-face { font-family: 'Faktum Medium Fina'; src: url('/fonts/Faktum-MediumFina.woff2') ... }

Until this file is present, the app falls back to the stack defined in the CSS
('Inter', -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif).
