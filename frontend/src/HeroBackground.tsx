import type { ReactNode } from 'react';
import './HeroBackground.css';

// Placeholder hero: a CSS-only layered "mountain silhouette" background.
// No real photos are wired in yet — to swap in a real hiking photo later,
// see the comment in HeroBackground.css (one background rule to replace).
export default function HeroBackground({ children }: { children: ReactNode }) {
  return (
    <div className="hero-background">
      <div className="hero-mountains" aria-hidden="true" />
      {children}
    </div>
  );
}
