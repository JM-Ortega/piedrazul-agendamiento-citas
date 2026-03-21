package co.edu.unicauca.piedrazul.backend.appointment.domain.model;

public enum AppointmentState {
    AGENDADA{
        @Override public boolean isActive(){
            return  true;
        }
    },
    ATENDIDA{
        @Override public boolean isActive(){
            return  false;
        }
    },
    CANCELADA{
        @Override public boolean isActive(){
            return  false;
        }
    },
    NO_ASISTIO{
        @Override public boolean isActive(){
            return  false;
        }
    },
    REPROGRAMADA{
        @Override public boolean isActive(){
            return  true;
        }
    };

    public abstract boolean isActive();
}
