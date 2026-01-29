// Placeholder beacon script using bleno (optional)
// Not installed by default. This file shows how to advertise a local name.
try{
  const bleno = require('bleno');
  const name = 'BUS_4711';
  bleno.on('stateChange', (state) => {
    if (state === 'poweredOn') {
      bleno.startAdvertising(name, []);
      console.log('Advertising', name);
    } else {
      bleno.stopAdvertising();
    }
  });
} catch (e){
  console.log('bleno not installed. This is a placeholder.');
}
