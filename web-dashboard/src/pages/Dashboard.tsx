import { useEffect, useState } from 'react';
import { collection, query, onSnapshot, doc, updateDoc } from 'firebase/firestore';
import { db } from '../firebase';
import { Smartphone, Battery, Clock, AlertTriangle, Search, Activity, Cpu, Terminal, X, RefreshCw } from 'lucide-react';
import { createPortal } from 'react-dom';

export default function Dashboard() {
  const [devices, setDevices] = useState<any[]>([]);
  const [loading, setLoading] = useState(true);
  const [search, setSearch] = useState('');
  const [selectedLogDevice, setSelectedLogDevice] = useState<any | null>(null);

  useEffect(() => {
    const q = query(collection(db, 'devices'));
    const unsubscribe = onSnapshot(q, (snapshot) => {
      const dev = snapshot.docs.map(doc => ({ id: doc.id, ...doc.data() }));
      setDevices(dev);
      setLoading(false);
    });
    return unsubscribe;
  }, []);

  // Update selected device if it changes in firestore while modal is open
  useEffect(() => {
    if (selectedLogDevice) {
      const updated = devices.find(d => d.id === selectedLogDevice.id);
      if (updated) setSelectedLogDevice(updated);
    }
  }, [devices]);

  const handleFetchLogs = async (deviceId: string) => {
    try {
      await updateDoc(doc(db, 'devices', deviceId), { logRequestPending: true });
    } catch (e) {
      console.error(e);
      alert('Failed to request logs');
    }
  };

  const filteredDevices = devices.filter(d =>
    d.id.toLowerCase().includes(search.toLowerCase()) ||
    (d.deviceModel || '').toLowerCase().includes(search.toLowerCase())
  );

  return (
    <div className="animate-fade-in">
      <header style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '40px' }}>
        <div>
          <h1 style={{ fontSize: '32px', marginBottom: '8px' }}>Device Fleet</h1>
          <p style={{ color: 'var(--text-secondary)' }}>Monitor and manage your kiosk devices across all locations.</p>
        </div>
        <div style={{ display: 'flex', gap: '16px' }}>
          <div style={{ position: 'relative', width: '300px' }}>
            <Search size={18} color="var(--text-secondary)" style={{ position: 'absolute', left: '12px', top: '50%', transform: 'translateY(-50%)' }} />
            <input
              type="text"
              className="input-field"
              placeholder="Search devices by ID or model..."
              value={search}
              onChange={(e) => setSearch(e.target.value)}
              style={{ paddingLeft: '40px' }}
            />
          </div>
        </div>
      </header>

      {/* Stats Summary */}
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(4, 1fr)', gap: '24px', marginBottom: '40px' }}>
        <StatCard title="Total Devices" value={devices.length} icon={<Smartphone size={20} color="var(--accent-blue)" />} />
        <StatCard title="Active Now" value={devices.filter(d => d.isOnline).length} icon={<Activity size={20} color="var(--accent-green)" />} />
        <StatCard title="Low Battery" value={devices.filter(d => d.batteryLevel < 20).length} icon={<Battery size={20} color="var(--accent-red)" />} />
        <StatCard title="Alerts" value={devices.reduce((acc, d) => acc + (d.alerts?.length || 0), 0)} icon={<AlertTriangle size={20} color="var(--accent-purple)" />} />
      </div>

      {/* Device List */}
      {loading ? (
        <div style={{ padding: '40px', textAlign: 'center', color: 'var(--text-secondary)' }}>Loading devices...</div>
      ) : filteredDevices.length === 0 ? (
        <div className="glass-card" style={{ textAlign: 'center', padding: '64px 24px' }}>
          <Smartphone size={48} color="var(--surface-border)" style={{ marginBottom: '16px' }} />
          <h3 style={{ fontSize: '20px', marginBottom: '8px' }}>No Devices Found</h3>
          <p style={{ color: 'var(--text-secondary)' }}>No devices match your search or none are registered yet.</p>
        </div>
      ) : (
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(350px, 1fr))', gap: '24px' }}>
          {filteredDevices.map(device => (
            <DeviceCard key={device.id} device={device} onShowLogs={() => setSelectedLogDevice(device)} />
          ))}
        </div>
      )}

      {/* Logs Modal */}
      {selectedLogDevice && createPortal(
        <div style={{
          position: 'fixed', top: 0, left: 0, right: 0, bottom: 0,
          background: 'rgba(0,0,0,0.7)', backdropFilter: 'blur(4px)',
          display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 9999
        }}>
          <div className="glass-panel animate-fade-in" style={{ width: '100%', maxWidth: '800px', padding: '32px', display: 'flex', flexDirection: 'column', height: '80vh' }}>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '24px' }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: '12px' }}>
                <Terminal size={24} color="var(--accent-blue)" />
                <h2 style={{ fontSize: '20px', margin: 0 }}>Device Logs: {selectedLogDevice.deviceModel}</h2>
              </div>
              <div style={{ display: 'flex', gap: '16px' }}>
                <button 
                  className="btn-primary" 
                  style={{ display: 'flex', alignItems: 'center', gap: '8px' }}
                  onClick={() => handleFetchLogs(selectedLogDevice.id)}
                  disabled={selectedLogDevice.logRequestPending}
                >
                  <RefreshCw size={16} className={selectedLogDevice.logRequestPending ? 'animate-spin' : ''} />
                  {selectedLogDevice.logRequestPending ? 'Fetching...' : 'Fetch Latest Logs'}
                </button>
                <button onClick={() => setSelectedLogDevice(null)} style={{ color: 'var(--text-secondary)', background: 'transparent', border: 'none', cursor: 'pointer' }}>
                  <X size={24} />
                </button>
              </div>
            </div>
            
            <div style={{
              flex: 1,
              background: '#0d1117',
              borderRadius: '12px',
              padding: '16px',
              overflowY: 'auto',
              fontFamily: 'monospace',
              fontSize: '12px',
              border: '1px solid rgba(255,255,255,0.1)',
              display: 'flex',
              flexDirection: 'column',
              gap: '4px'
            }}>
              {selectedLogDevice.activityLog && selectedLogDevice.activityLog.length > 0 ? (
                selectedLogDevice.activityLog.map((line: string, i: number) => (
                  <div key={i} style={{ 
                    color: line.includes('FAILED') || line.includes('Error') ? '#ff7b72' : 
                           line.includes('SUCCESS') || line.includes('CONFIRMED') ? '#3fb950' : 
                           '#8b949e',
                    lineHeight: '1.4'
                  }}>
                    {line}
                  </div>
                ))
              ) : (
                <div style={{ color: '#8b949e', textAlign: 'center', marginTop: '40px' }}>
                  No logs available for this device. Click "Fetch Latest Logs" to pull from the tablet.
                </div>
              )}
            </div>
          </div>
        </div>,
        document.body
      )}
    </div>
  );
}

function StatCard({ title, value, icon }: { title: string, value: number, icon: React.ReactNode }) {
  return (
    <div className="glass-card" style={{ display: 'flex', alignItems: 'center', gap: '16px' }}>
      <div style={{ width: '48px', height: '48px', borderRadius: '12px', background: 'var(--surface-border)', display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
        {icon}
      </div>
      <div>
        <p style={{ color: 'var(--text-secondary)', fontSize: '12px', textTransform: 'uppercase', letterSpacing: '0.5px', marginBottom: '4px' }}>{title}</p>
        <h3 style={{ fontSize: '24px', margin: 0 }}>{value}</h3>
      </div>
    </div>
  );
}

function DeviceCard({ device, onShowLogs }: { device: any, onShowLogs: () => void }) {
  const isOnline = device.isOnline;
  const battery = device.batteryLevel || 0;

  return (
    <div className="glass-card">
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', marginBottom: '20px' }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: '12px' }}>
          <div style={{ width: '40px', height: '40px', borderRadius: '10px', background: 'rgba(255,255,255,0.05)', display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
            <Smartphone size={20} color={isOnline ? 'var(--accent-green)' : 'var(--text-secondary)'} />
          </div>
          <div>
            <h4 style={{ fontSize: '16px', margin: '0 0 4px 0' }}>{device.deviceModel || 'Unknown Device'}</h4>
            <p style={{ fontSize: '12px', color: 'var(--text-secondary)', fontFamily: 'monospace' }}>{device.id.substring(0, 8)}...</p>
          </div>
        </div>
        <span className={`badge ${isOnline ? 'badge-active' : 'badge-offline'}`}>
          {isOnline ? 'Online' : 'Offline'}
        </span>
      </div>

      <div style={{ display: 'flex', flexDirection: 'column', gap: '12px', marginBottom: '24px' }}>
        <InfoRow icon={<Battery size={14} />} label="Battery" value={`${battery}%`} valueColor={battery < 20 ? 'var(--accent-red)' : 'var(--text-primary)'} />
        <InfoRow icon={<Cpu size={14} />} label="App Version" value={device.kioskAppVersion || 'Unknown'} />
        <InfoRow icon={<Clock size={14} />} label="Last Seen" value={device.lastHeartbeat ? new Date(device.lastHeartbeat.seconds * 1000).toLocaleString() : 'Never'} />
      </div>

      <div style={{ display: 'flex', gap: '12px' }}>
        <button
          className="btn-secondary"
          style={{ flex: 1 }}
          onClick={() => {
            const navigate = (window as any)._navigate || (() => { window.location.href = `/${device.id}` });
            navigate(`/${device.id}`);
          }}
        >
          Details
        </button>
        <button className="btn-secondary" style={{ flex: 1 }} onClick={onShowLogs}>Logs</button>
      </div>
    </div>
  );
}

function InfoRow({ icon, label, value, valueColor = 'var(--text-primary)' }: any) {
  return (
    <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: '8px', color: 'var(--text-secondary)' }}>
        {icon}
        <span style={{ fontSize: '13px' }}>{label}</span>
      </div>
      <span style={{ fontSize: '13px', fontWeight: 500, color: valueColor }}>{value}</span>
    </div>
  );
}
