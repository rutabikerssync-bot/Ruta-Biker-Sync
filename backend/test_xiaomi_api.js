async function test() {
  const res = await fetch('http://localhost:3000/v1/groups', {
    headers: {
      'x-user-id': '2d2c72d8-d567-4ebd-a9d7-509fde115d0c'
    }
  });
  const json = await res.json();
  const grp = json.memberGroups?.[0];
  console.log('GROUP FOR XIAOMI USER:');
  console.log('name:', grp?.name);
  console.log('tier:', grp?.tier);
  console.log('colorsDescription:', grp?.colorsDescription);
  console.log('directivaScope:', grp?.directivaScope);
  console.log('statusKind:', grp?.statusKind);
  console.log('clubRole:', grp?.clubRole);
  console.log('logoUrl startsWith data:image?', grp?.logoUrl?.startsWith('data:image'));
  console.log('logoUrl length:', grp?.logoUrl?.length);
}
test();
