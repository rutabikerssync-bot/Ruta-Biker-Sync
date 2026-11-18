async function test() {
  const res = await fetch('http://localhost:3000/v1/groups/9b68b42e-ac31-43a9-9411-5b7088381097/members', {
    headers: {
      'x-user-id': '2d2c72d8-d567-4ebd-a9d7-509fde115d0c'
    }
  });
  const json = await res.json();
  console.log('GET MEMBERS RESPONSE:', JSON.stringify(json, null, 2));
}
test();
