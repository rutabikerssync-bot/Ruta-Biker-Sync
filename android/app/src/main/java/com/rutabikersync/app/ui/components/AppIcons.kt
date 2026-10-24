package com.rutabikersync.app.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.BluetoothSearching
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.StarHalf
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.BatteryStd
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Emergency
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.GpsNotFixed
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.HowToVote
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.NearMe
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.PersonOff
import androidx.compose.material.icons.filled.PersonRemove
import androidx.compose.material.icons.filled.PinDrop
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.TwoWheeler
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.Warning
import androidx.compose.ui.graphics.vector.ImageVector
import com.rutabikersync.app.R

/**
 * Catálogo centralizado de iconos de Ruta Biker Sync (ICONS.md §1–§16).
 * Regla 5 de ICONS.md: Diferenciar siempre Cargo (estrellas/llaves), Estatus (círculos/relojes) y Rango (medallas).
 * Regla 2 de ICONS.md: El color nunca es la única señal (semáforos llevan CheckCircle ✓, Warning ⚠, Cancel ✕).
 */
object AppIcons {
    // 2. Navegación
    val TabRides: ImageVector = Icons.Default.TwoWheeler
    val TabGroups: ImageVector = Icons.Default.Groups
    val TabRoutes: ImageVector = Icons.Default.Route
    val TabProfile: ImageVector = Icons.Default.AccountCircle
    val Back: ImageVector = Icons.AutoMirrored.Filled.ArrowBack
    val Close: ImageVector = Icons.Default.Close
    val More: ImageVector = Icons.Default.MoreVert
    val Settings: ImageVector = Icons.Default.Settings
    val Help: ImageVector = Icons.AutoMirrored.Filled.HelpOutline
    val Search: ImageVector = Icons.Default.Search
    val Refresh: ImageVector = Icons.Default.Refresh

    // 6. Grupos, estatus, cargos y roles (ICONS.md §6)
    @DrawableRes val LeaderCrownShieldRes: Int = R.drawable.ic_leader_crown_shield
    @DrawableRes val FounderFlameRes: Int = R.drawable.ic_founder_flame
    @DrawableRes val MeshConnectedRes: Int = R.drawable.ic_mesh_connected
    @DrawableRes val RoadMarshalConeRes: Int = R.drawable.ic_road_marshal_cone

    val LeaderFallback: ImageVector = Icons.Default.Shield

    // Estatus de membresía (RULES R2)
    val StatusFullMember: ImageVector = Icons.Default.Verified
    val StatusProspect: ImageVector = Icons.Default.HourglassTop
    val StatusGuest: ImageVector = Icons.Default.Badge

    // Cargos del club (RULES R3.1)
    val RolePresident: ImageVector = Icons.Default.Star
    val RoleVicePresident: ImageVector = Icons.AutoMirrored.Filled.StarHalf
    val RoleRoadCaptain: ImageVector = Icons.Default.Explore
    val RoleSergeantAtArms: ImageVector = Icons.Default.Security
    val RoleSecretary: ImageVector = Icons.Default.EditNote
    val RoleTreasurer: ImageVector = Icons.Default.AccountBalanceWallet

    // Acciones de grupo
    val InviteMember: ImageVector = Icons.Default.PersonAdd
    val RemoveMember: ImageVector = Icons.Default.PersonRemove
    val TransferGroup: ImageVector = Icons.Default.SwapHoriz
    val QrCode: ImageVector = Icons.Default.QrCode
    val QrScanner: ImageVector = Icons.Default.QrCodeScanner
    val Vote: ImageVector = Icons.Default.HowToVote
    val Report: ImageVector = Icons.Default.Flag

    // 7. Rangos e insignias
    val BadgeGeneric: ImageVector = Icons.Default.EmojiEvents

    // 10. Perfil
    val AvatarPhoto: ImageVector = Icons.Default.AddAPhoto
    val Nickname: ImageVector = Icons.Default.Badge
    val Bike: ImageVector = Icons.Default.TwoWheeler
    val MapColor: ImageVector = Icons.Default.Palette
    val Edit: ImageVector = Icons.Default.Edit
    val Logout: ImageVector = Icons.AutoMirrored.Filled.Logout

    // 11. Semáforos de pago (ICONS.md §1 regla 2 y §11: forma distinta además de color)
    val PaymentGreen: ImageVector = Icons.Default.CheckCircle
    val PaymentYellow: ImageVector = Icons.Default.Warning
    val PaymentRed: ImageVector = Icons.Default.Cancel

    // Campo BLE
    val BleFieldTest: ImageVector = Icons.AutoMirrored.Filled.BluetoothSearching

    // 3, 4 y 5. Rodada activa, Mesh y Estado del rider en el mapa (ICONS.md §3–§5)
    val PttMic: ImageVector = Icons.Default.Mic
    val SosAlert: ImageVector = Icons.Default.Emergency
    val QuickSignals: ImageVector = Icons.Default.Campaign
    val MyLocation: ImageVector = Icons.Default.MyLocation
    val FollowLeader: ImageVector = Icons.Default.NearMe
    val MapLayers: ImageVector = Icons.Default.Layers
    val ZoomIn: ImageVector = Icons.Default.Add
    val ZoomOut: ImageVector = Icons.Default.Remove
    val GpsFixed: ImageVector = Icons.Default.GpsFixed
    val GpsNotFixed: ImageVector = Icons.Default.GpsNotFixed
    val RiderOffMesh: ImageVector = Icons.Default.PersonOff
    val RoleScout: ImageVector = Icons.Default.Visibility
    val RoleSweep: ImageVector = Icons.Default.Flag
    val RoleCaptain: ImageVector = Icons.Default.Star
    val SelfHeading: ImageVector = Icons.Default.Navigation
    val LeaderPin: ImageVector = Icons.Default.PinDrop
    val BatteryStd: ImageVector = Icons.Default.BatteryStd
}
