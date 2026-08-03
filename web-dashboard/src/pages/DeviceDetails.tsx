import { useEffect, useState } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { doc, getDoc, collection, query, orderBy, limit, getDocs, updateDoc } from 'firebase/firestore';
import { db } from '../firebase';
import { ArrowLeft, Activity, Wifi, Smartphone, Image as ImageIcon, Save } from 'lucide-react';

export default function DeviceDetails() {
  const { id } = useParams();
  const navigate = useNavigate();
  const [device, setDevice] = useState<any>(null);
  const [telemetry, setTelemetry] = useState<any>(null);
  const [loading, setLoading] = useState(true);
  
  const [deviceName, setDeviceName] = useState('');
  const [wallpaperUrl, setWallpaperUrl] = useState('');
  const [savingConfig, setSavingConfig] = useState(false);

  useEffect(() => {
    async function fetchData() {
      if (!id) return;
      try {
        // Fetch device status
        const docRef = doc(db, 'devices', id);
        const docSnap = await getDoc(docRef);
        if (docSnap.exists()) {
          const data = docSnap.data();
          setDevice({ id: docSnap.id, ...data });
          setWallpaperUrl(data.wallpaperUrl || '');
          setDeviceName(data.deviceName || '');
        }

        // Fetch latest telemetry
        const telQuery = query(collection(db, 'devices', id, 'telemetry'), orderBy('date', 'desc'), limit(1));
        const telSnap = await getDocs(telQuery);
        if (!telSnap.empty) {
          setTelemetry(telSnap.docs[0].data());
        }
      } catch (e) {
        console.error("Error fetching device details", e);
      } finally {
        setLoading(false);
      }
    }
    fetchData();
  }, [id]);

  const handleSaveConfig = async () => {
    if (!id) return;
    setSavingConfig(true);
    try {
      await updateDoc(doc(db, 'devices', id), {
        wallpaperUrl: wallpaperUrl,
        deviceName: deviceName
      });
      alert('Device configuration updated');
    } catch (e) {
      console.error(e);
      alert('Failed to update config');
    } finally {
      setSavingConfig(false);
    }
  };

  const formatBytes = (bytes: number) => {
    if (bytes === 0) return '0 B';
    const k = 1024;
    const sizes = ['B', 'KB', 'MB', 'GB', 'TB'];
    const i = Math.floor(Math.log(bytes) / Math.log(k));
    return parseFloat((bytes / Math.pow(k, i)).toFixed(2)) + ' ' + sizes[i];
  };

  const formatTime = (ms: number) => {
    const totalMinutes = Math.floor(ms / 60000);
    const hours = Math.floor(totalMinutes / 60);
    const minutes = totalMinutes % 60;
    if (hours > 0) return `${hours}h ${minutes}m`;
    return `${minutes}m`;
  };

  if (loading) return <div style={{ padding: '40px', color: 'var(--text-secondary)' }}>Loading...</div>;
  if (!device) return <div style={{ padding: '40px', color: 'var(--accent-red)' }}>Device not found</div>;

  return (
    <div className="animate-fade-in">
      <header style={{ display: 'flex', alignItems: 'center', gap: '16px', marginBottom: '40px' }}>
        <button onClick={() => navigate('/')} className="btn-secondary" style={{ padding: '8px' }}>
          <ArrowLeft size={20} />
        </button>
        <div>
          <h1 style={{ fontSize: '32px', margin: 0 }}>{device.deviceModel || 'Unknown Device'}</h1>
          <p style={{ color: 'var(--text-secondary)', margin: '4px 0 0 0' }}>ID: {device.id}</p>
        </div>
      </header>

      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '24px', marginBottom: '32px' }}>
        {/* Network Telemetry Card */}
        <div className="glass-panel" style={{ padding: '24px' }}>
          <h2 style={{ fontSize: '18px', display: 'flex', alignItems: 'center', gap: '8px', marginBottom: '24px' }}>
            <Activity size={20} color="var(--accent-blue)" /> Daily Network Usage
          </h2>
          
          <div style={{ display: 'flex', gap: '24px' }}>
            <div style={{ flex: 1, background: 'rgba(255,255,255,0.02)', padding: '16px', borderRadius: '12px', border: '1px solid var(--surface-border)' }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: '8px', color: 'var(--text-secondary)', marginBottom: '8px' }}>
                <Wifi size={16} /> WiFi Data
              </div>
              <div style={{ fontSize: '24px', fontWeight: 600 }}>
                {telemetry ? formatBytes(telemetry.wifiBytes || 0) : 'No Data'}
              </div>
            </div>
            
            <div style={{ flex: 1, background: 'rgba(255,255,255,0.02)', padding: '16px', borderRadius: '12px', border: '1px solid var(--surface-border)' }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: '8px', color: 'var(--text-secondary)', marginBottom: '8px' }}>
                <Smartphone size={16} /> Mobile Data
              </div>
              <div style={{ fontSize: '24px', fontWeight: 600 }}>
                {telemetry ? formatBytes(telemetry.mobileBytes || 0) : 'No Data'}
              </div>
            </div>
          </div>
        </div>

        {/* Configuration Card */}
        <div className="glass-panel" style={{ padding: '24px' }}>
          <h2 style={{ fontSize: '18px', display: 'flex', alignItems: 'center', gap: '8px', marginBottom: '24px' }}>
            <ImageIcon size={20} color="var(--accent-green)" /> Configuration & Hardware
          </h2>
          
          <div style={{ marginBottom: '16px', display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '16px' }}>
            <div style={{ background: 'rgba(255,255,255,0.02)', padding: '12px', borderRadius: '8px', border: '1px solid var(--surface-border)' }}>
              <div style={{ fontSize: '12px', color: 'var(--text-secondary)', textTransform: 'uppercase' }}>Serial Number</div>
              <div style={{ fontSize: '14px', fontWeight: 'bold' }}>{device.serialNumber || 'Unknown'}</div>
            </div>
            <div style={{ background: 'rgba(255,255,255,0.02)', padding: '12px', borderRadius: '8px', border: '1px solid var(--surface-border)' }}>
              <div style={{ fontSize: '12px', color: 'var(--text-secondary)', textTransform: 'uppercase' }}>IMEI</div>
              <div style={{ fontSize: '14px', fontWeight: 'bold' }}>{device.imei || 'Unknown'}</div>
            </div>
          </div>
          
          <div style={{ marginBottom: '16px' }}>
            <label style={{ display: 'block', marginBottom: '8px', fontSize: '12px', color: 'var(--text-secondary)', textTransform: 'uppercase' }}>Device Name</label>
            <input 
              type="text" 
              className="input-field" 
              placeholder="Front Desk Tablet 1" 
              value={deviceName} 
              onChange={e => setDeviceName(e.target.value)} 
            />
          </div>
          
          <div style={{ marginBottom: '16px' }}>
            <label style={{ display: 'block', marginBottom: '8px', fontSize: '12px', color: 'var(--text-secondary)', textTransform: 'uppercase' }}>Background Wallpaper URL</label>
            <input 
              type="url" 
              className="input-field" 
              placeholder="https://example.com/wallpaper.jpg" 
              value={wallpaperUrl} 
              onChange={e => setWallpaperUrl(e.target.value)} 
            />
          </div>
          
          <button 
            className="btn-primary" 
            onClick={handleSaveConfig} 
            disabled={savingConfig}
            style={{ display: 'flex', alignItems: 'center', gap: '8px' }}
          >
            <Save size={16} /> {savingConfig ? 'Saving...' : 'Save Config'}
          </button>
        </div>

        {/* Security & Access Card */}
        <div className="glass-panel" style={{ padding: '24px' }}>
          <h2 style={{ fontSize: '18px', display: 'flex', alignItems: 'center', gap: '8px', marginBottom: '24px' }}>
            <Activity size={20} color="var(--accent-purple)" /> Security & Access
          </h2>
          
          <div style={{ marginBottom: '16px' }}>
            {device.authRequestPending && !device.advancedUnlocked && (
              <div style={{ padding: '12px', background: 'rgba(255, 193, 7, 0.1)', border: '1px solid rgba(255, 193, 7, 0.3)', borderRadius: '8px', color: '#ffc107', marginBottom: '16px' }}>
                <strong>Alert:</strong> The device is requesting access to Advanced Settings.
              </div>
            )}
            
            <p style={{ color: 'var(--text-secondary)', fontSize: '14px', marginBottom: '16px' }}>
              Status: {device.advancedUnlocked ? 
                <span style={{ color: 'var(--accent-green)', fontWeight: 'bold' }}>Unlocked</span> : 
                <span style={{ color: 'var(--text-secondary)' }}>Locked</span>}
            </p>
          </div>
          
          <div style={{ display: 'flex', gap: '12px' }}>
            {!device.advancedUnlocked ? (
              <button 
                className="btn-primary" 
                onClick={async () => await updateDoc(doc(db, 'devices', device.id), { advancedUnlocked: true, authRequestPending: false })}
                style={{ background: 'var(--accent-green)', color: '#000' }}
              >
                Approve & Unlock
              </button>
            ) : (
              <button 
                className="btn-secondary" 
                onClick={async () => await updateDoc(doc(db, 'devices', device.id), { advancedUnlocked: false })}
                style={{ border: '1px solid var(--accent-red)', color: 'var(--accent-red)' }}
              >
                Revoke & Lock
              </button>
            )}
          </div>
        </div>
      </div>

      {/* App Usage Table */}
      <div className="glass-panel" style={{ overflow: 'hidden' }}>
        <h2 style={{ fontSize: '18px', padding: '24px', margin: 0, borderBottom: '1px solid var(--surface-border)' }}>
          Daily App Usage
        </h2>
        <table style={{ width: '100%', borderCollapse: 'collapse' }}>
          <thead>
            <tr style={{ borderBottom: '1px solid var(--surface-border)', background: 'rgba(255,255,255,0.02)' }}>
              <th style={{ padding: '16px 24px', textAlign: 'left', color: 'var(--text-secondary)', fontWeight: 500, fontSize: '13px' }}>Package Name</th>
              <th style={{ padding: '16px 24px', textAlign: 'right', color: 'var(--text-secondary)', fontWeight: 500, fontSize: '13px' }}>Active Time (Foreground)</th>
            </tr>
          </thead>
          <tbody>
            {telemetry?.appUsage && Object.keys(telemetry.appUsage).length > 0 ? (
              Object.entries(telemetry.appUsage).map(([pkg, ms]: [string, any]) => (
                <tr key={pkg} style={{ borderBottom: '1px solid var(--surface-border)' }}>
                  <td style={{ padding: '16px 24px', fontWeight: 500 }}>{pkg}</td>
                  <td style={{ padding: '16px 24px', textAlign: 'right', color: 'var(--accent-blue)', fontWeight: 600 }}>
                    {formatTime(ms)}
                  </td>
                </tr>
              ))
            ) : (
              <tr>
                <td colSpan={2} style={{ padding: '48px', textAlign: 'center', color: 'var(--text-secondary)' }}>
                  No app usage data recorded yet.
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>
    </div>
  );
}
