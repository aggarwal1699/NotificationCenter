create extension if not exists pgcrypto;

create table if not exists public.households (
 id uuid primary key default gen_random_uuid(), name text not null check(char_length(name) between 1 and 80),
 created_by uuid not null references auth.users(id), created_at timestamptz not null default now()
);
create table if not exists public.household_members (
 household_id uuid not null references public.households(id) on delete cascade,
 user_id uuid not null references auth.users(id) on delete cascade, display_name text not null,
 floor text not null default 'Other', role text not null default 'member' check(role in ('admin','member')),
 joined_at timestamptz not null default now(), primary key(household_id,user_id)
);
create table if not exists public.household_invites (
 id uuid primary key default gen_random_uuid(), household_id uuid not null references public.households(id) on delete cascade,
 code_hash text not null unique, expires_at timestamptz not null, revoked_at timestamptz,
 created_by uuid not null references auth.users(id), created_at timestamptz not null default now()
);
create table if not exists public.alerts (
 id uuid primary key default gen_random_uuid(), household_id uuid not null references public.households(id) on delete cascade,
 reporter_id uuid not null references auth.users(id), reporter_name text not null, floor text not null,
 status text not null default 'active' check(status in ('active','acknowledged','in_progress','resolved','dismissed')),
 created_at timestamptz not null default now(), resolved_at timestamptz, resolved_by uuid references auth.users(id)
);
create table if not exists public.alert_responses (
 id uuid primary key default gen_random_uuid(), alert_id uuid not null references public.alerts(id) on delete cascade,
 user_id uuid not null references auth.users(id), display_name text not null, floor text not null,
 response_type text not null check(response_type in ('acknowledged','motor_off','switching_off','not_my_floor','not_responsible','need_help')),
 message text not null, created_at timestamptz not null default now()
);
create table if not exists public.user_devices (
 id uuid primary key default gen_random_uuid(), user_id uuid not null references auth.users(id) on delete cascade,
 household_id uuid not null references public.households(id) on delete cascade,
 fcm_token text not null unique, updated_at timestamptz not null default now()
);
create index if not exists alerts_household_created_idx on public.alerts(household_id,created_at desc);
create index if not exists responses_alert_created_idx on public.alert_responses(alert_id,created_at);

create or replace function public.is_household_member(hid uuid)
returns boolean language sql stable security definer set search_path=public
as $$ select exists(select 1 from household_members where household_id=hid and user_id=auth.uid()) $$;
create or replace function public.is_household_admin(hid uuid)
returns boolean language sql stable security definer set search_path=public
as $$ select exists(select 1 from household_members where household_id=hid and user_id=auth.uid() and role='admin') $$;

alter table public.households enable row level security;
alter table public.household_members enable row level security;
alter table public.household_invites enable row level security;
alter table public.alerts enable row level security;
alter table public.alert_responses enable row level security;
alter table public.user_devices enable row level security;

create policy "household members read household" on public.households for select using(public.is_household_member(id));
create policy "household members read members" on public.household_members for select using(public.is_household_member(household_id));
create policy "household members read alerts" on public.alerts for select using(public.is_household_member(household_id));
create policy "household members read responses" on public.alert_responses for select using(exists(select 1 from alerts a where a.id=alert_id and public.is_household_member(a.household_id)));
create policy "users read own devices" on public.user_devices for select using(user_id=auth.uid());
create policy "users upsert own devices" on public.user_devices for insert with check(user_id=auth.uid() and public.is_household_member(household_id));
create policy "users update own devices" on public.user_devices for update using(user_id=auth.uid()) with check(user_id=auth.uid() and public.is_household_member(household_id));

create or replace function public.create_household(p_name text,p_floor text default 'Other')
returns table(household_id uuid,invite_code text) language plpgsql security definer set search_path=public
as $$
declare hid uuid; code text;
begin
 if auth.uid() is null then raise exception 'Authentication required'; end if;
 insert into households(name,created_by) values(trim(p_name),auth.uid()) returning id into hid;
 insert into household_members(household_id,user_id,display_name,floor,role)
 values(hid,auth.uid(),coalesce(auth.jwt()->'user_metadata'->>'name',split_part(auth.jwt()->>'email','@',1)),coalesce(p_floor,'Other'),'admin');
 code:=upper(substr(encode(gen_random_bytes(8),'hex'),1,10));
 insert into household_invites(household_id,code_hash,expires_at,created_by)
 values(hid,encode(digest(code,'sha256'),'hex'),now()+interval '7 days',auth.uid());
 return query select hid,code;
end $$;

create or replace function public.join_household(p_code text,p_floor text default 'Other')
returns table(household_id uuid) language plpgsql security definer set search_path=public
as $$
declare inv household_invites%rowtype;
begin
 if auth.uid() is null then raise exception 'Authentication required'; end if;
 select * into inv from household_invites where code_hash=encode(digest(upper(trim(p_code)),'sha256'),'hex')
 and revoked_at is null and expires_at>now() limit 1;
 if inv.id is null then raise exception 'Invitation code is invalid or expired'; end if;
 insert into household_members(household_id,user_id,display_name,floor)
 values(inv.household_id,auth.uid(),coalesce(auth.jwt()->'user_metadata'->>'name',split_part(auth.jwt()->>'email','@',1)),coalesce(p_floor,'Other'))
 on conflict(household_id,user_id) do update set floor=excluded.floor;
 return query select inv.household_id;
end $$;

create or replace function public.create_water_alert(p_household_id uuid)
returns table(alert_id uuid) language plpgsql security definer set search_path=public
as $$
declare m household_members%rowtype; aid uuid;
begin
 select * into m from household_members where household_id=p_household_id and user_id=auth.uid();
 if m.user_id is null then raise exception 'Not a household member'; end if;
 if exists(select 1 from alerts where household_id=p_household_id and status in ('active','acknowledged','in_progress') and created_at>now()-interval '90 seconds') then
  raise exception 'An active alert was just created. Please use the existing alert.';
 end if;
 insert into alerts(household_id,reporter_id,reporter_name,floor)
 values(p_household_id,auth.uid(),m.display_name,m.floor) returning id into aid;
 return query select aid;
end $$;

create or replace function public.respond_to_alert(p_alert_id uuid,p_response_type text,p_message text)
returns uuid language plpgsql security definer set search_path=public
as $$
declare a alerts%rowtype; m household_members%rowtype; rid uuid; next_status text;
begin
 select * into a from alerts where id=p_alert_id;
 if a.id is null or not public.is_household_member(a.household_id) then raise exception 'Alert not found'; end if;
 select * into m from household_members where household_id=a.household_id and user_id=auth.uid();
 if a.status in ('resolved','dismissed') then raise exception 'Alert is already closed'; end if;
 insert into alert_responses(alert_id,user_id,display_name,floor,response_type,message)
 values(a.id,auth.uid(),m.display_name,m.floor,p_response_type,p_message) returning id into rid;
 next_status:=case when p_response_type='switching_off' then 'in_progress' when a.status='active' then 'acknowledged' else a.status end;
 update alerts set status=next_status where id=a.id;
 return rid;
end $$;

create or replace function public.resolve_alert(p_alert_id uuid)
returns void language plpgsql security definer set search_path=public
as $$
declare a alerts%rowtype;
begin
 select * into a from alerts where id=p_alert_id for update;
 if a.id is null or not public.is_household_member(a.household_id) then raise exception 'Alert not found'; end if;
 if not public.is_household_admin(a.household_id) then raise exception 'Only household admins can resolve alerts'; end if;
 update alerts set status='resolved',resolved_at=now(),resolved_by=auth.uid() where id=a.id;
end $$;

grant execute on function public.create_household(text,text) to authenticated;
grant execute on function public.join_household(text,text) to authenticated;
grant execute on function public.create_water_alert(uuid) to authenticated;
grant execute on function public.respond_to_alert(uuid,text,text) to authenticated;
grant execute on function public.resolve_alert(uuid) to authenticated;
grant execute on function public.is_household_member(uuid) to authenticated;
grant execute on function public.is_household_admin(uuid) to authenticated;
