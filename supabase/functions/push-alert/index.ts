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
  const {alert_id,event_type="alert"}=await req.json();
  const admin=createClient(Deno.env.get("SUPABASE_URL")!,Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!);
  const {data:alert,error}=await admin.from("alerts").select("*").eq("id",alert_id).single();
  if(error||!alert) return Response.json({error:"Alert not found"},{status:404,headers:cors});
  const {data:membership}=await admin.from("household_members").select("user_id").eq("household_id",alert.household_id).eq("user_id",user.id).maybeSingle();
  if(!membership) return Response.json({error:"Not a household member"},{status:403,headers:cors});
  let title="WATER OVERFLOW ALERT!";
  let body=`Overflow reported by ${alert.reporter_name} • ${alert.floor}. Please check the motor.`;
  if(event_type==="alert" && alert.reporter_id!==user.id) return Response.json({error:"Only the reporter can publish this alert"},{status:403,headers:cors});
  if(event_type==="response") {
   const {data:response}=await admin.from("alert_responses").select("display_name,floor,message").eq("alert_id",alert_id).eq("user_id",user.id).order("created_at",{ascending:false}).limit(1).maybeSingle();
   if(!response) return Response.json({error:"No matching response found"},{status:403,headers:cors});
   title="WaterWatch household update";
   body=`${response.display_name} (${response.floor}): ${response.message}`;
  }
  if(event_type==="resolved") {
   if(alert.resolved_by!==user.id) return Response.json({error:"Only the resolver can publish this update"},{status:403,headers:cors});
   const {data:resolver}=await admin.from("household_members").select("display_name").eq("household_id",alert.household_id).eq("user_id",user.id).maybeSingle();
   title="WaterWatch alert resolved";
   body=`The overflow alert was marked resolved by ${resolver?.display_name ?? "a household admin"}.`;
  }
  if(!["alert","response","resolved"].includes(event_type)) return Response.json({error:"Invalid event type"},{status:400,headers:cors});
  const {data:members}=await admin.from("household_members").select("user_id").eq("household_id",alert.household_id).neq("user_id",user.id);
  const ids=(members??[]).map((m:{user_id:string})=>m.user_id);
  if(!ids.length) return Response.json({sent:0,message:"No other household members"},{headers:cors});
  const {data:devices}=await admin.from("user_devices").select("fcm_token").in("user_id",ids);
  const tokens=[...new Set((devices??[]).map((d:{fcm_token:string})=>d.fcm_token))];
  if(!tokens.length) return Response.json({sent:0,message:"No registered recipient devices"},{headers:cors});
  const serviceAccount=JSON.parse(Deno.env.get("FIREBASE_SERVICE_ACCOUNT_JSON")!);
  const googleAuth=new GoogleAuth({credentials:serviceAccount,scopes:["https://www.googleapis.com/auth/firebase.messaging"]});
  const accessToken=await googleAuth.getAccessToken();
  const results=await Promise.all(tokens.map(async(token:string)=>{
   const response=await fetch(`https://fcm.googleapis.com/v1/projects/${Deno.env.get("FIREBASE_PROJECT_ID")}/messages:send`,{
    method:"POST",headers:{Authorization:`Bearer ${accessToken}`,"Content-Type":"application/json"},
    body:JSON.stringify({message:{token,notification:{title,body},data:{alert_id:alert.id,event_type},android:{priority:"high"}}})
   });
   return {token,ok:response.ok};
  }));
  const bad=results.filter(r=>!r.ok).map(r=>r.token);
  if(bad.length) await admin.from("user_devices").delete().in("fcm_token",bad);
  return Response.json({sent:results.filter(r=>r.ok).length,failed:bad.length},{headers:cors});
 } catch(e) { return Response.json({error:String(e)},{status:500,headers:cors}); }
});
