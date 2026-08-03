import { Outlet, NavLink } from 'react-router-dom';
import { LayoutDashboard, Smartphone, LogOut, Package } from 'lucide-react';
import { auth } from '../firebase';
import { signOut } from 'firebase/auth';

export default function Layout() {
  const handleLogout = () => {
    signOut(auth);
  };

  return (
    <div className="app-container animate-fade-in">
      <aside className="sidebar">
        <div style={{ display: 'flex', alignItems: 'center', gap: '12px', marginBottom: '48px' }}>
          <div style={{ width: '32px', height: '32px', borderRadius: '8px', background: 'linear-gradient(135deg, var(--accent-blue), var(--accent-purple))', display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
            <Smartphone size={18} color="#fff" />
          </div>
          <h2 style={{ fontSize: '20px', margin: 0, color: '#fff' }}>Eiko Kiosk</h2>
        </div>

        <nav style={{ display: 'flex', flexDirection: 'column', gap: '8px', flex: 1 }}>
          <NavLink 
            to="/" 
            end
            style={({ isActive }) => ({
              display: 'flex', alignItems: 'center', gap: '12px', padding: '12px 16px',
              borderRadius: '8px',
              color: isActive ? '#fff' : 'var(--text-secondary)',
              background: isActive ? 'var(--surface-hover)' : 'transparent',
              fontWeight: isActive ? 500 : 400,
              transition: 'all 0.2s'
            })}
          >
            <LayoutDashboard size={18} />
            Device Fleet
          </NavLink>
          
          <NavLink 
            to="/apps" 
            style={({ isActive }) => ({
              display: 'flex', alignItems: 'center', gap: '12px', padding: '12px 16px',
              borderRadius: '8px',
              color: isActive ? '#fff' : 'var(--text-secondary)',
              background: isActive ? 'var(--surface-hover)' : 'transparent',
              fontWeight: isActive ? 500 : 400,
              transition: 'all 0.2s'
            })}
          >
            <Package size={18} />
            App Management
          </NavLink>
        </nav>

        <button 
          onClick={handleLogout}
          style={{
            display: 'flex', alignItems: 'center', gap: '12px', padding: '12px 16px',
            color: 'var(--text-secondary)', width: '100%', textAlign: 'left',
            borderRadius: '8px'
          }}
          onMouseOver={(e) => e.currentTarget.style.color = 'var(--accent-red)'}
          onMouseOut={(e) => e.currentTarget.style.color = 'var(--text-secondary)'}
        >
          <LogOut size={18} />
          Sign Out
        </button>
      </aside>

      <main className="main-content">
        <Outlet />
      </main>
    </div>
  );
}
