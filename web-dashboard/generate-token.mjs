import { initializeApp, cert } from 'firebase-admin/app';
import { getAuth } from 'firebase-admin/auth';
import { readFileSync } from 'fs';

// Load the service account JSON securely
const serviceAccount = JSON.parse(readFileSync('../service-account.json', 'utf8'));

initializeApp({
  credential: cert(serviceAccount)
});

const uid = 'kiosk-device-01';

getAuth().createCustomToken(uid)
  .then((customToken) => {
    console.log('\n--- YOUR CUSTOM FIREBASE TOKEN ---');
    console.log(customToken);
    console.log('----------------------------------\n');
    process.exit(0);
  })
  .catch((error) => {
    console.error('Error creating custom token:', error);
    process.exit(1);
  });
