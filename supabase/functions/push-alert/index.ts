import { createClient } from "https://esm.sh/@supabase/supabase-js@2";
import { GoogleAuth } from "npm:google-auth-library@9";

const cors = { "Access-Control-Allow-Origin": "*", "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type", "Access-Control-Allow-Methods": "POST, OPTIONS" };
Deno.serve(async (req) => {
 if (req.method === "OPTIONS") return new Response("ok",{headers:cors});
 try {
  const authHeader=req.headers.get("Authorization")??"";
  const userClient=createClient(Deno.env.get("SUPABASE_URL")!,Deno.env.get("SUPABASE_ANON_KEY")!,{global:{headers:{Authorization:authHeader}}});
  const {data:{user},error:authError}=await userClient.auth.getUser();
  if(authError||!user) return Response.json({error:"Unauthorized"},{status:401,headers:cors});
  const {alert_id}=await req.json();
  const admin=createClient(Deno.env.get("SUPABASE_URL")!,Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!);
  const {data:alert,error}=await admin.from("alerts").select("*").eq("id",alert_id).single();
  if(error||!alert||alert.reporter_id!==user.id) return Response.json({error:"Alert not found or not yours"},{status:403,headers:cors});
  const {data:members}=await admin.from("household_members").select("user_id").eq("household_id",alert.household_id).neq("user_id",user.id);
  const ids=(members??[]).map((m:{user_id:string})=>m.user_id);
  const {data:devices}=await admin.from("user_devices").select("fcm_token").in("user_id",ids);
  const tokens=[...new Set((devices??[]).map((d:{fcm_token:string})=>d.fcm_token))];
  if(!tokens.length) return Response.json({sent:0,message:"No registered recipient devices"},{headers:cors});
  const serviceAccount=JSON.parse(Deno.env.get("FIREBASE_SERVICE_ACCOUNT_JSON")!);
  const googleAuth=new GoogleAuth({credentials:serviceAccount,scopes:["https://www.googleapis.com/auth/firebase.messaging"]});
  const accessToken=await googleAuth.getAccessToken();
  const results=await Promise.all(tokens.map(async(token:string)=>{
   const response=await fetch(`https://fcm.googleapis.com/v1/projects/${Deno.env.get("FIREBASE_PROJECT_ID")}/messages:send`,{
    method:"POST",headers:{Authorization:`Bearer ${accessToken}`,"Content-Type":"application/json"},
    body:JSON.stringify({message:{token,notification:{title:"WATER OVERFLOW ALERT!",body:`Overflow reported by ${alert.reporter_name} • ${alert.floor}. Please check the motor.`},data:{alert_id:alert.id},android:{priority:"high"}}})
   });
   return {token,ok:response.ok};
  }));
  const bad=results.filter(r=>!r.ok).map(r=>r.token);
  if(bad.length) await admin.from("user_devices").delete().in("fcm_token",bad);
  return Response.json({sent:results.filter(r=>r.ok).length,failed:bad.length},{headers:cors});
 } catch(e) { return Response.json({error:String(e)},{status:500,headers:cors}); }
});
